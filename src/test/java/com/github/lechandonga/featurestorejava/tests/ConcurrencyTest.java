package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.model.AuditEntry;
import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.ConsistencyReport;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
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

/**
 * Concurrent writes, backfills and reads.
 *
 * <p>Reads must never see a half-applied recompute or throw, online/offline
 * consistency must hold for every sampled point, and rejected (duplicate /
 * conflicting) writes must not add any point.
 */
class ConcurrencyTest {

    private static final long T0 = StoreFixture.T0;
    private static final int ENTITIES = 8;
    private static final int ROUNDS = 20;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void concurrentWritesBackfillsAndReadsStayConsistent() throws Exception {
        StoreFixture fx = new StoreFixture();
        fx.registry.register(new RegisterRequest("user", "uid", null, sampleFeatures(), "d"));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger acceptedWrites = new AtomicInteger();
        AtomicInteger rejectedWrites = new AtomicInteger();

        // writer threads: one event time per entity per round; duplicates across
        // threads are expected to be rejected and must not corrupt state
        for (int t = 0; t < 3; t++) {
            final int writer = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        long clock = T0 + round * 10_000L + writer;
                        fx.clock.set(clock);
                        List<WriteRow> rows = new ArrayList<>();
                        for (int e = 0; e < ENTITIES; e++) {
                            long eventTime = T0 + round * 1000L + e;
                            rows.add(new WriteRow(eventTime,
                                    f("uid", "u" + e, "age", round * 100 + e + writer)));
                        }
                        try {
                            fx.writes.submit(new WriteBatch("user", 1, rows));
                            acceptedWrites.incrementAndGet();
                        } catch (BatchRejectedException ok) {
                            rejectedWrites.incrementAndGet();
                        }
                    }
                } catch (Throwable th) {
                    errors.add(th);
                }
            });
        }

        // backfill thread: repeatedly corrects the most recent WRITE point of u0
        pool.submit(() -> {
            try {
                start.await();
                String lastOriginal = null;
                for (int round = 0; round < ROUNDS; round++) {
                    Thread.sleep(2);
                    long clock = T0 + 900_000L + round;
                    fx.clock.set(clock);
                    List<StoredPoint> points = fx.state.offline().forFeatureSet("user", 1).stream()
                            .filter(p -> p.entity().equals("u0"))
                            .filter(p -> p.source().equals("WRITE"))
                            .sorted((a, b) -> Long.compare(b.eventTime(), a.eventTime()))
                            .toList();
                    if (points.isEmpty() || points.get(0).id().equals(lastOriginal)) {
                        continue;
                    }
                    StoredPoint target = points.get(0);
                    lastOriginal = target.id();
                    try {
                        fx.backfills.backfill(new BackfillRequest("user", 1,
                                "recompute-" + round, List.of(new Correction(target.id(), "u0",
                                target.eventTime(), f("age", 9000 + round), "recompute"))));
                    } catch (BatchRejectedException ignored) {
                        // a competing round may have corrected this point first
                    }
                }
            } catch (Throwable th) {
                errors.add(th);
            }
        });

        // reader threads: hammer online + offline consistency; never expect
        // a torn read or an exception
        for (int t = 0; t < 2; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 400; i++) {
                        long asOf = T0 + (long) (Math.random() * 900_000);
                        List<EntityAtPoint> targets = new ArrayList<>();
                        for (int e = 0; e < ENTITIES; e++) {
                            targets.add(new EntityAtPoint("u" + e, asOf));
                        }
                        ConsistencyReport report = fx.consistency.check("user", 1, targets);
                        if (!report.equivalent()) {
                            throw new AssertionError("inconsistency: " + report.issues());
                        }
                    }
                } catch (Throwable th) {
                    errors.add(th);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads did not finish");
        assertEquals(List.of(), new ArrayList<>(errors), "no torn reads / errors");
        assertTrue(acceptedWrites.get() > 0, "some writes succeeded");
        assertTrue(rejectedWrites.get() > 0, "duplicate writers were rejected");

        // final invariant: online chain equals offline-derived latest for u0..u7
        List<EntityAtPoint> finalTargets = new ArrayList<>();
        for (int e = 0; e < ENTITIES; e++) {
            finalTargets.add(new EntityAtPoint("u" + e, T0 + 20_000_000L));
        }
        ConsistencyReport after = fx.consistency.check("user", 1, finalTargets);
        assertTrue(after.equivalent(), after.issues().toString());

        // audit trail captured decisions with entity/time/reason
        List<AuditEntry> rejected = fx.audit.recent(500).stream()
                .filter(a -> "REJECTED".equals(a.decision())).toList();
        assertTrue(rejected.stream().anyMatch(a -> a.reason() != null && !a.reason().isBlank()));
    }
}
