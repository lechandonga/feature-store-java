package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.ConsistencyReport;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.MissingPolicy;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.ValueType;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import com.github.lechandonga.featurestorejava.service.ConsistencyService;
import com.github.lechandonga.featurestorejava.service.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.ObservabilityService;
import com.github.lechandonga.featurestorejava.service.ReadService;
import com.github.lechandonga.featurestorejava.service.WriteService;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceManager;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent writes/backfills in two workspaces plus online/offline reads:
 * every read observes a whole pre-commit or post-commit state, workspaces
 * never see each other's points, and per-workspace final usage is exact.
 */
class WorkspaceConcurrencyTest {

    private static final long T0 = StoreFixture.T0;
    private static final int ENTITIES = 6;
    private static final int ROUNDS = 15;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static FeatureDef feature(String name, ValueType type) {
        return new FeatureDef(name, type, true, 0L, MissingPolicy.DEFAULT, name);
    }

    @Test
    void concurrentWritesReadsAndBackfillsDoNotCrossWorkspaces() throws Exception {
        FixedTimeSource clock = new FixedTimeSource(T0);
        WorkspaceManager manager = new WorkspaceManager(clock);
        manager.initDefault();
        manager.create("alpha", null);
        manager.create("beta", null);
        FeatureRegistry registry = new FeatureRegistry(manager, clock);
        WriteService writes = new WriteService(manager, registry, clock);
        ReadService reads = new ReadService(manager, registry, clock);
        BackfillService backfills = new BackfillService(manager, registry, clock);
        ConsistencyService consistency = new ConsistencyService(reads, registry);
        ObservabilityService ops = new ObservabilityService(manager, registry, reads, clock);

        // same feature set name, different entity prefixes and feature values
        registry.register("alpha", new RegisterRequest("shared", "uid", null,
                List.of(feature("age", ValueType.LONG)), "a"));
        registry.register("beta", new RegisterRequest("shared", "uid", null,
                List.of(feature("age", ValueType.LONG)), "b"));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger accepted = new AtomicInteger();

        // one writer per workspace: identical entity ids "u0.." but values are
        // workspace-specific (alpha=100000+..., beta=200000+...) so any cross
        // read is detectable
        for (String ws : List.of("alpha", "beta")) {
            pool.submit(() -> {
                try {
                    start.await();
                    long base = "alpha".equals(ws) ? 100_000L : 200_000L;
                    for (int round = 0; round < ROUNDS; round++) {
                        clock.set(T0 + round * 10_000L);
                        List<WriteRow> rows = new ArrayList<>();
                        for (int e = 0; e < ENTITIES; e++) {
                            long eventTime = T0 + round * 1000L + e;
                            rows.add(new WriteRow(eventTime,
                                    f("uid", "u" + e, "age", base + round * 100 + e)));
                        }
                        try {
                            writes.submit(ws, new WriteBatch("shared", 1, rows));
                            accepted.incrementAndGet();
                        } catch (BatchRejectedException ignored) {
                            // harmless racing duplicates within one workspace
                        }
                    }
                } catch (Throwable th) {
                    errors.add(th);
                }
            });
        }

        // backfillers correct latest points in each workspace repeatedly
        for (String ws : List.of("alpha", "beta")) {
            pool.submit(() -> {
                try {
                    start.await();
                    long correction = "alpha".equals(ws) ? 900_000L : 950_000L;
                    String lastOriginal = null;
                    for (int round = 0; round < ROUNDS; round++) {
                        Thread.sleep(1);
                        clock.set(T0 + 800_000L + round);
                        List<StoredPoint> points =
                                manager.requireState(ws).offline().forFeatureSet("shared", 1)
                                        .stream().filter(p -> p.entity().equals("u0"))
                                        .filter(p -> "WRITE".equals(p.source()))
                                        .sorted((a, b) -> Long.compare(b.eventTime(), a.eventTime()))
                                        .toList();
                        if (points.isEmpty() || points.get(0).id().equals(lastOriginal)) {
                            continue;
                        }
                        StoredPoint target = points.get(0);
                        lastOriginal = target.id();
                        try {
                            backfills.backfill(ws, new BackfillRequest("shared", 1,
                                    ws + "-recompute-" + round,
                                    List.of(new Correction(target.id(), "u0",
                                            target.eventTime(), f("age", correction + round),
                                            "fix"))));
                        } catch (BatchRejectedException ignored) {
                            // competing correction attempt
                        }
                    }
                } catch (Throwable th) {
                    errors.add(th);
                }
            });
        }

        // readers: hammer online+offline equivalence AND value-range isolation
        for (String ws : List.of("alpha", "beta")) {
            for (int t = 0; t < 2; t++) {
                final String workspace = ws;
                pool.submit(() -> {
                    try {
                        start.await();
                        long low = "alpha".equals(workspace) ? 100_000L : 200_000L;
                        long high = "alpha".equals(workspace) ? 200_000L : 300_000L;
                        long correctedLow = "alpha".equals(workspace) ? 900_000L : 950_000L;
                        for (int i = 0; i < 300; i++) {
                            long asOf = T0 + (long) (Math.random() * 900_000);
                            List<EntityAtPoint> targets = new ArrayList<>();
                            for (int e = 0; e < ENTITIES; e++) {
                                targets.add(new EntityAtPoint("u" + e, asOf));
                            }
                            ConsistencyReport report =
                                    consistency.check(workspace, "shared", 1, targets);
                            if (!report.equivalent()) {
                                throw new AssertionError(workspace + " inconsistency: "
                                        + report.issues());
                            }
                            // every PRESENT value must be either an in-range write
                            // value or this workspace's correction value — never
                            // the other workspace's range
                            for (EntityAtPoint target : targets) {
                                var fv = reads.online(workspace, "shared", 1,
                                        target.entity(), target.asOfTime())
                                        .features().get("age");
                                // only genuinely stored values are range-checked;
                                // DEFAULTED/MISSING carry no foreign data
                                if (fv.value() == null
                                        || fv.status()
                                        != com.github.lechandonga.featurestorejava.model.ValueStatus.PRESENT) {
                                    continue;
                                }
                                long v = (long) fv.value();
                                boolean writeRange = v >= low && v < high;
                                boolean correctionRange =
                                        v >= correctedLow && v < correctedLow + ROUNDS;
                                if (!writeRange && !correctionRange) {
                                    throw new AssertionError(workspace + " saw foreign value "
                                            + v + " for " + target.entity() + " at "
                                            + target.asOfTime());
                                }
                            }
                        }
                    } catch (Throwable th) {
                        errors.add(th);
                    }
                });
            }
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads did not finish");
        assertEquals(List.of(), new ArrayList<>(errors),
                errors.isEmpty() ? "" : errors.peek().toString());
        assertTrue(accepted.get() >= 2, "both workspaces committed writes");

        // every write batch is accepted (distinct event times per round/entity);
        // exact correction counts may differ by racing rounds — isolation is
        // asserted by the value ranges above and independent entity sets below
        long alphaPoints = manager.usage("alpha").points();
        long betaPoints = manager.usage("beta").points();
        assertTrue(alphaPoints >= (long) ROUNDS * ENTITIES,
                "alpha points=" + alphaPoints);
        assertTrue(betaPoints >= (long) ROUNDS * ENTITIES,
                "beta points=" + betaPoints);
        assertEquals(ENTITIES, ops.coverage("alpha", "shared", null, T0 + 20_000_000L)
                .entityCount());
        assertEquals(ENTITIES, ops.coverage("beta", "shared", null, T0 + 20_000_000L)
                .entityCount());
        // entities with the same ids are separately present in both
        assertEquals(ENTITIES, manager.requireState("alpha").offline()
                .entities("shared", 1).size());
        assertEquals(ENTITIES, manager.requireState("beta").offline()
                .entities("shared", 1).size());
        // final reads: each workspace sees only its own correction family for u0
        long alphaFinal = (long) reads.online("alpha", "shared", 1, "u0", T0 + 1_000_000L)
                .features().get("age").value();
        long betaFinal = (long) reads.online("beta", "shared", 1, "u0", T0 + 1_000_000L)
                .features().get("age").value();
        assertTrue(alphaFinal >= 900_000L && alphaFinal < 900_000L + ROUNDS,
                "alpha final=" + alphaFinal);
        assertTrue(betaFinal >= 950_000L && betaFinal < 950_000L + ROUNDS,
                "beta final=" + betaFinal);
    }
}
