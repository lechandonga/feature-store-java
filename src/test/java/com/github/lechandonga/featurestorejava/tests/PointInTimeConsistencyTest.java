package com.github.lechandonga.featurestorejava.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import com.github.lechandonga.featurestorejava.domain.DiscrepancyType;
import com.github.lechandonga.featurestorejava.domain.MissingPolicy;
import com.github.lechandonga.featurestorejava.domain.ValueStatus;
import com.github.lechandonga.featurestorejava.domain.ValueType;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.*;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PointInTimeConsistencyTest {

    private FeatureRegistry registry;
    private IngestService ingest;
    private BackfillService backfill;
    private OnlineServingService online;
    private OfflineServingService offline;
    private ConsistencyService consistency;
    private DecisionLogger logger;

    @BeforeEach
    void setUp() {
        registry = new FeatureRegistry();
        FeatureStorage storage = new FeatureStorage();
        logger = new DecisionLogger();
        WriteCoordinator coordinator = new WriteCoordinator();
        ingest = new IngestService(registry, storage, coordinator, logger);
        backfill = new BackfillService(registry, storage, coordinator, logger);
        FeatureLookupService lookup = new FeatureLookupService(storage, logger);
        online = new OnlineServingService(registry, lookup, storage);
        offline = new OfflineServingService(registry, lookup, storage);
        consistency = new ConsistencyService(registry, lookup, logger, storage);
        registry.register("price", 1, ValueType.DOUBLE, MissingPolicy.DEFAULT, 0.0, null, 0L);
    }

    @Test
    void futureDataIsInvisibleBeforeItsEventTime() {
        long now = System.currentTimeMillis();
        long t1 = now - 200_000;
        long t2 = now - 100_000;
        assertTrue(ingest.write("b", List.of(
                new RowInput("price", 1, "e1", t1, 100.0, t1))).accepted());
        assertTrue(ingest.write("b", List.of(
                new RowInput("price", 1, "e1", t2, 200.0, t2))).accepted());

        var atT1 = offline.historical(new Observation("e1", t1, List.of("price"), List.of(1)));
        var atT2 = offline.historical(new Observation("e1", t2, List.of("price"), List.of(1)));
        assertEquals(100.0, atT1.get(0).value());
        assertEquals(200.0, atT2.get(0).value());
        // As of before any data: nothing visible, default reported explicitly (not silent).
        var before = offline.historical(new Observation("e1", t1 - 1, List.of("price"), List.of(1)));
        assertEquals(ValueStatus.DEFAULTED, before.get(0).status());
    }

    @Test
    void lateIngestDoesNotLeakIntoHistoricalAsOfAndBacktestIsReproducible() {
        // Backfill channel carries a corrected old value, committed "later" (revisionTs = now).
        long oldEventTs = System.currentTimeMillis() - 1_000_000;
        long trainAsOf = oldEventTs + 50_000; // training time point before correction was committed
        assertTrue(backfill.backfill("bf",
                List.of(new RowInput("price", 1, "e2", oldEventTs, 42.0))).accepted());

        var first = offline.historical(new Observation("e2", trainAsOf, List.of("price"), List.of(1)));
        var second = offline.historical(new Observation("e2", trainAsOf, List.of("price"), List.of(1)));
        // revisionTs > trainAsOf -> correction invisible at the historical time point
        assertEquals(ValueStatus.DEFAULTED, first.get(0).status());
        assertEquals(first.get(0).value(), second.get(0).value());
        assertEquals(first.get(0).status(), second.get(0).status(), "historical results must reproduce");
    }

    @Test
    void correctionBecomesVisibleAfterRevisionTimeAndIsExplainable() {
        long oldEventTs = System.currentTimeMillis() - 1_000_000;
        assertTrue(backfill.backfill("bf",
                List.of(new RowInput("price", 1, "e3", oldEventTs, 77.0))).accepted());
        long now = System.currentTimeMillis();
        var v = online.read("e3", List.of("price"), List.of(1)).get(0);
        assertEquals(77.0, v.value());
        assertNotNull(v.revisionTsMs());
        assertNotNull(v.origin());
        assertTrue(v.explanation().contains("revisionTs"), "served value must explain provenance");
    }

    @Test
    void onlineAndOfflineEquivalentAtSameTimePoint() {
        long t = System.currentTimeMillis() - 5_000;
        assertTrue(ingest.write("b", List.of(new RowInput("price", 1, "e4", t, 9.9))).accepted());
        long asOf = System.currentTimeMillis();
        var on = online.read("e4", List.of("price"), List.of(1));
        var off = offline.historical(new Observation("e4", asOf, List.of("price"), List.of(1)));
        assertEquals(on.get(0).value(), off.get(0).value());
        assertEquals(on.get(0).status(), off.get(0).status());
        assertEquals(on.get(0).eventTsMs(), off.get(0).eventTsMs());

        var report = consistency.check(new Observation("e4", asOf, List.of("price"), List.of(1)));
        assertEquals(ConsistencyReport.Status.MATCH, report.status());
        assertEquals(DiscrepancyType.MATCH, report.items().get(0).discrepancy());
    }

    @Test
    void consistencyDetectsDiscrepancyAtTimePointBeforeLateCorrection() {
        long oldEventTs = System.currentTimeMillis() - 1_000_000;
        long historicalAsOf = oldEventTs + 50_000;
        assertTrue(backfill.backfill("bf",
                List.of(new RowInput("price", 1, "e5", oldEventTs, 5.0))).accepted());
        // ONLINE sees the correction; AS_OF at the old time point must not: explicit classification.
        var report = consistency.check(new Observation("e5", historicalAsOf, List.of("price"), List.of(1)));
        assertEquals(ConsistencyReport.Status.MISMATCH, report.status());
        assertNotEquals(DiscrepancyType.MATCH, report.items().get(0).discrepancy());
    }
}
