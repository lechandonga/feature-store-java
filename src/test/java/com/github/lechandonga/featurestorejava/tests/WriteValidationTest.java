package com.github.lechandonga.featurestorejava.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import com.github.lechandonga.featurestorejava.domain.RejectReason;
import com.github.lechandonga.featurestorejava.domain.MissingPolicy;
import com.github.lechandonga.featurestorejava.domain.ValueType;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import com.github.lechandonga.featurestorejava.service.DecisionLogger;
import com.github.lechandonga.featurestorejava.service.IngestService;
import com.github.lechandonga.featurestorejava.service.RowInput;
import com.github.lechandonga.featurestorejava.service.WriteCoordinator;
import com.github.lechandonga.featurestorejava.service.WriteResult;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WriteValidationTest {

    private FeatureRegistry registry;
    private FeatureStorage storage;
    private IngestService ingest;
    private BackfillService backfill;
    private DecisionLogger logger;

    @BeforeEach
    void setUp() {
        registry = new FeatureRegistry();
        storage = new FeatureStorage();
        logger = new DecisionLogger();
        WriteCoordinator coordinator = new WriteCoordinator();
        ingest = new IngestService(registry, storage, coordinator, logger);
        backfill = new BackfillService(registry, storage, coordinator, logger);
        registry.register("price", 1, ValueType.DOUBLE, MissingPolicy.DEFAULT, 0.0, null, 0L);
    }

    private RowInput row(long eventTs, Object value) {
        return new RowInput("price", 1, "item-1", eventTs, value);
    }

    @Test
    void acceptsValidBatch() {
        long t = System.currentTimeMillis() - 10_000;
        WriteResult result = ingest.write("b1", List.of(row(t, 12.5)));
        assertTrue(result.accepted());
        assertEquals(1, result.acceptedRows());
    }

    @Test
    void missingValueRejectedWithDistinctReason() {
        WriteResult result = ingest.write("b2", List.of(new RowInput("price", 1, "item-1", 1000L, null)));
        assertFalse(result.accepted());
        assertEquals(RejectReason.MISSING_VALUE, result.violations().get(0).reason());
        assertTrue(storage.scan("price", 1, "item-1").isEmpty());
    }

    @Test
    void typeMismatchRejected() {
        WriteResult result = ingest.write("b3", List.of(row(1000L, "not-a-number")));
        assertFalse(result.accepted());
        assertEquals(RejectReason.TYPE_MISMATCH, result.violations().get(0).reason());
    }

    @Test
    void futureEventTimeRejected() {
        WriteResult result = ingest.write("b4", List.of(row(System.currentTimeMillis() + 60_000, 1.0)));
        assertFalse(result.accepted());
        assertEquals(RejectReason.FUTURE_EVENT_TIME, result.violations().get(0).reason());
    }

    @Test
    void duplicateWriteRejected() {
        long t = System.currentTimeMillis() - 10_000;
        assertTrue(ingest.write("b5", List.of(row(t, 1.0))).accepted());
        WriteResult dup = ingest.write("b6", List.of(row(t, 2.0)));
        assertFalse(dup.accepted());
        assertEquals(RejectReason.DUPLICATE_WRITE, dup.violations().get(0).reason());
        // original value untouched
        assertEquals(1.0, storage.scan("price", 1, "item-1").get(0).value());
    }

    @Test
    void outOfOrderLiveWriteRejectedButBackfillChannelAcceptsLateData() {
        long now = System.currentTimeMillis() - 10_000;
        long older = now - 100_000;
        assertTrue(ingest.write("b7", List.of(row(now, 5.0))).accepted());
        WriteResult late = ingest.write("b8", List.of(row(older, 4.0)));
        assertFalse(late.accepted());
        assertEquals(RejectReason.OUT_OF_ORDER, late.violations().get(0).reason());
        assertTrue(storage.scan("price", 1, "item-1").size() == 1);

        WriteResult corrected = backfill.backfill("b9", List.of(row(older, 4.0)));
        assertTrue(corrected.accepted());
        assertEquals(2, storage.scan("price", 1, "item-1").size());
    }

    @Test
    void oneBadRowRejectsEntireBatchAtomicNoPartialCommit() {
        long t = System.currentTimeMillis() - 20_000;
        List<RowInput> rows = List.of(
                row(t, 11.0),
                new RowInput("price", 1, "item-1", t + 1000, null), // missing value
                row(t + 2000, 13.0));
        WriteResult result = ingest.write("b10", rows);
        assertFalse(result.accepted(), "batch must be rejected as a whole");
        assertEquals(1, result.violations().size());
        assertEquals(RejectReason.MISSING_VALUE, result.violations().get(0).reason());
        assertTrue(storage.scan("price", 1, "item-1").isEmpty(),
                "none of the valid rows may partially take effect");
    }
}
