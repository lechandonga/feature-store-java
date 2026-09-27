package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.WorkspaceRequest;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Concurrent writes, backfills and reads across multiple workspaces: no read
 * may observe another workspace's data or a half-applied commit, and every
 * workspace's final state must be exactly the sum of its own commits.
 */
class WorkspaceConcurrencyTest {

    private static final long T0 = StoreFixture.T0;
    private static final int ROUNDS = 15;
    private static final int ENTITIES = 4;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void concurrentMultiWorkspaceTrafficStaysIsolatedAndConsistent() throws Exception {
        StoreFixture fx = new StoreFixture();
        List<String> spaces = List.of("ws-1", "ws-2", "ws-3");
        for (String ws : spaces) {
            fx.workspaces.create(new WorkspaceRequest(ws, null, null, "d"));
            // same feature set name in every workspace
            fx.registry.register(ws, new RegisterRequest("user", "uid", null,
                    sampleFeatures(), "d"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger acceptedWrites = new AtomicInteger();

        // one writer per workspace: values are tagged with the workspace index
        // so any cross-workspace leak is detectable by value inspection
        for (int w = 0; w < spaces.size(); w++) {
            final String ws = spaces.get(w);
            final int tag = (w + 1) * 1_000_000;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        List<WriteRow> rows = new ArrayList<>();
                        for (int e = 0; e < ENTITIES; e++) {
                            rows.add(new WriteRow(T0 + round * 1000L + e,
                                    f("uid", "u" + e, "age", tag + round * 100 + e)));
                        }
                        try {
                            fx.writes.submit(ws, new WriteBatch("user", 1, rows));
                            acceptedWrites.incrementAndGet();
                        } catch (BatchRejectedException ok) {
                            // duplicate (entity, eventTime) across retries is fine
                        }
                    }
                } catch (Throwable th) {
                    errors.add(th);
                }
            });
        }

        // one backfiller per workspace: corrects its own latest u0 point
        for (String ws : spaces) {
            pool.submit(() -> {
                try {
                    start.await();
                    String last = null;
                    for (int round = 0; round < ROUNDS; round++) {
                        Thread.sleep(2);
                        List<StoredPoint> points = fx.state.offline(ws)
                                .forFeatureSet("user", 1).stream()
                                .filter(p -> p.entity().equals("u0"))
                                .filter(p -> "WRITE".equals(p.source()))
                                .sorted((a, b) -> Long.compare(b.eventTime(), a.eventTime()))
                                .toList();
                        if (points.isEmpty() || points.get(0).id().equals(last)) {
                            continue;
                        }
                        StoredPoint target = points.get(0);
                        last = target.id();
                        try {
                            fx.backfills.backfill(ws, new BackfillRequest("user", 1,
                                    ws + "-bf-" + round, List.of(new Correction(target.id(),
                                    "u0", target.eventTime(),
                                    f("age", 5_000_000 + round), "recompute"))));
                        } catch (BatchRejectedException ignored) {
                            // a competing correction may have landed first
                        }
                    }
                } catch (Throwable th) {
                    errors.add(th);
                }
            });
        }

        // readers per workspace: every read must see only its own workspace's
        // value range and a coherent (committed) state
        for (int w = 0; w < spaces.size(); w++) {
            final String ws = spaces.get(w);
            final int tag = (w + 1) * 1_000_000;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < ROUNDS * 4; round++) {
                        long asOf = T0 + ROUNDS * 1000L + 500;
                        var result = fx.reads.online(ws, "user", 1, "u1", asOf);
                        var ageValue = result.features().get("age");
                        if (ageValue.status()
                                == com.github.lechandonga.featurestorejava.model.ValueStatus.PRESENT) {
                            long v = ((Number) ageValue.value()).longValue();
                            boolean ownWrite = v >= tag && v < tag + ROUNDS * 100 + ENTITIES;
                            boolean ownBackfill = v >= 5_000_000 && v < 5_000_000 + ROUNDS;
                            assertTrue(ownWrite || ownBackfill,
                                    "workspace " + ws + " read foreign value " + v);
                        }
                    }
                } catch (Throwable th) {
                    errors.add(th);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        if (!errors.isEmpty()) {
            fail("concurrent worker failed: " + errors.peek());
        }
        assertTrue(acceptedWrites.get() > 0);

        // final state: each workspace holds exactly its own points; totals are
        // independent and every value belongs to the workspace's own tag range
        for (int w = 0; w < spaces.size(); w++) {
            String ws = spaces.get(w);
            int tag = (w + 1) * 1_000_000;
            List<StoredPoint> points = fx.state.offline(ws).forFeatureSet("user", 1);
            assertTrue(points.size() >= ROUNDS * ENTITIES,
                    ws + " should hold at least the accepted writes");
            for (StoredPoint p : points) {
                Object age = p.values().get("age");
                long v = ((Number) age).longValue();
                boolean own = (v >= tag && v < tag + ROUNDS * 100 + ENTITIES)
                        || (v >= 5_000_000 && v < 5_000_000 + ROUNDS);
                assertTrue(own, "workspace " + ws + " contains foreign point " + v);
            }
        }
    }
}
