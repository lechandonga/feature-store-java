package com.github.lechandonga.featurestorejava.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.lechandonga.featurestorejava.domain.MissingPolicy;
import com.github.lechandonga.featurestorejava.domain.ValueType;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.*;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.junit.jupiter.api.Test;

class ConcurrentBackfillTest {

    /**
     * A recompute batch always updates both f1 and f2 of an entity to the same round number in one
     * atomic commit. Concurrent readers must therefore always observe either the old pair (1,1) or a
     * matched new pair (n,n) for each entity — never a mixed pair (a half-applied batch).
     * Independent threads simultaneously write unrelated features/entities and issue invalid
     * batches, which must be rejected as wholes and never change state.
     */
    @Test
    void concurrentBackfillWritesAndReadsNeverExposeHalfUpdate() throws Exception {
        FeatureRegistry registry = new FeatureRegistry();
        FeatureStorage storage = new FeatureStorage();
        DecisionLogger logger = new DecisionLogger();
        WriteCoordinator coordinator = new WriteCoordinator();
        IngestService ingest = new IngestService(registry, storage, coordinator, logger);
        BackfillService backfill = new BackfillService(registry, storage, coordinator, logger);
        FeatureLookupService lookup = new FeatureLookupService(storage, logger);
        OnlineServingService onlineSvc = new OnlineServingService(registry, lookup, storage);

        registry.register("f1", 1, ValueType.INT, MissingPolicy.DEFAULT, 0, null, 0L);
        registry.register("f2", 1, ValueType.INT, MissingPolicy.DEFAULT, 0, null, 0L);
        registry.register("noise", 1, ValueType.INT, MissingPolicy.DEFAULT, 0, null, 0L);

        int entities = 20;
        for (int e = 0; e < entities; e++) {
            long t = System.currentTimeMillis() - 10_000;
            WriteResult seed = ingest.write("seed-" + e, List.of(
                    new RowInput("f1", 1, "e" + e, t, 1),
                    new RowInput("f2", 1, "e" + e, t, 1)));
            assertTrue(seed.accepted());
        }

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicInteger readRounds = new AtomicInteger();

        // One recompute writer: f1 + f2 of ALL entities per batch, atomically.
        pool.submit(() -> {
            try {
                start.await();
                for (int round = 1; round <= 30; round++) {
                    List<RowInput> rows = new ArrayList<>();
                    for (int e = 0; e < entities; e++) {
                        long eventTs = System.currentTimeMillis() - 1000 - e;
                        rows.add(new RowInput("f1", 1, "e" + e, eventTs, round));
                        rows.add(new RowInput("f2", 1, "e" + e, eventTs, round));
                    }
                    WriteResult r = backfill.recompute("recompute-" + round, rows);
                    // recompute of same (entity,eventTs,origin) later rounds => eventTs differs
                    // because time advanced; only exact duplicate rounds could reject, which is fine.
                    if (!r.accepted() && r.violations().stream()
                            .anyMatch(v -> v.reason() != com.github.lechandonga.featurestorejava.domain.RejectReason.DUPLICATE_WRITE)) {
                        failures.add("unexpected recompute rejection: " + r.violations());
                    }
                }
            } catch (Exception ex) {
                failures.add("recompute writer: " + ex);
            }
        });

        // Noise live writers on an unrelated feature (strictly increasing event times).
        for (int w = 0; w < 2; w++) {
            int worker = w;
            pool.submit(() -> {
                try {
                    start.await();
                    long t = System.currentTimeMillis() - 200_000;
                    for (int i = 0; i < 200; i++) {
                        t += 1000;
                        ingest.write("noise-" + worker + "-" + i, List.of(
                                new RowInput("noise", 1, "n" + worker, t, i)));
                    }
                } catch (Exception ex) {
                    failures.add("noise writer: " + ex);
                }
            });
        }

        // Invalid-batch writer: every batch contains one bad row; it must be rejected wholesale.
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    long t = System.currentTimeMillis() - 5_000 - i;
                    WriteResult r = ingest.write("bad-" + i, List.of(
                            new RowInput("noise", 1, "bad-entity", t, 7),
                            new RowInput("noise", 1, "bad-entity", t, "wrong-type")));
                    if (r.accepted()) {
                        failures.add("invalid batch must never be accepted");
                    }
                }
            } catch (Exception ex) {
                failures.add("bad writer: " + ex);
            }
        });

        // Readers: f1 and f2 of an entity come from the same atomic batch => equal values.
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 400; i++) {
                    for (int e = 0; e < entities; e++) {
                        List<FeatureValue> vals = onlineSvc.read("e" + e,
                                List.of("f1", "f2"), List.of(1, 1));
                        if (!vals.get(0).value().equals(vals.get(1).value())) {
                            failures.add("half-update for entity e" + e + ": f1="
                                    + vals.get(0).value() + " f2=" + vals.get(1).value());
                        }
                    }
                    readRounds.incrementAndGet();
                }
            } catch (Exception ex) {
                failures.add("reader: " + ex);
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads did not finish in time");
        assertTrue(failures.isEmpty(), "concurrency failures: " + failures);
        assertTrue(readRounds.get() > 0);
        // Invalid batches never committed:
        assertTrue(new OnlineServingService(registry, lookup, storage)
                .read("bad-entity", List.of("noise"), List.of(1)).get(0).status()
                != com.github.lechandonga.featurestorejava.domain.ValueStatus.RECORDED,
                "rejected batch rows must not become visible");
    }
}
