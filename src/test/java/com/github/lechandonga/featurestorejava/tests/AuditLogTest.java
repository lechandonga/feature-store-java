package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.model.AuditEntry;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.RejectionReason;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every acceptance and rejection is explainable: the audit trail must carry the
 * feature set/version, entity, event time point and a concrete reason.
 */
class AuditLogTest {

    private static final long T0 = StoreFixture.T0;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void acceptedWriteRecordsEntityVersionAndPointIds() {
        StoreFixture fx = new StoreFixture();
        fx.registry.register(new RegisterRequest("user", "uid", null, sampleFeatures(), "d"));
        fx.clock.set(T0);
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 10)))));
        AuditEntry entry = fx.audit.recent(10).stream()
                .filter(e -> "WRITE".equals(e.action()) && "ACCEPTED".equals(e.decision()))
                .findFirst().orElseThrow();
        assertEquals("user", entry.featureSet());
        assertEquals(1, entry.version());
        assertTrue(entry.reason().contains("committed"));
        Object ids = entry.evidence().get("pointIds");
        assertTrue(ids instanceof List && !((List<?>) ids).isEmpty());
    }

    @Test
    void rejectedBatchRecordsReasonEntityAndEventTime() {
        StoreFixture fx = new StoreFixture();
        fx.registry.register(new RegisterRequest("user", "uid", null, sampleFeatures(), "d"));
        assertThrows(BatchRejectedException.class, () -> fx.writes.submit(
                new WriteBatch("user", 1, List.of(
                        new WriteRow(T0 + 100, f("uid", "u9", "age", "bad-type"))))));
        AuditEntry entry = fx.audit.recent(10).stream()
                .filter(e -> "WRITE_ROW".equals(e.action()))
                .findFirst().orElseThrow();
        assertEquals("u9", entry.entity());
        assertEquals(T0 + 100, entry.eventTime());
        assertTrue(entry.reason().startsWith(RejectionReason.TYPE_MISMATCH.name()));
        // a batch-level summary is also recorded
        AuditEntry summary = fx.audit.recent(10).stream()
                .filter(e -> "WRITE".equals(e.action()) && "REJECTED".equals(e.decision()))
                .findFirst().orElseThrow();
        assertEquals(1, summary.evidence().get("violations"));
    }

    @Test
    void perRowRejectionsCanBeFilteredByEntityAndCarryTimeAndReason() {
        StoreFixture fx = new StoreFixture();
        fx.registry.register(new RegisterRequest("user", "uid", null, sampleFeatures(), "d"));
        assertThrows(BatchRejectedException.class, () -> fx.writes.submit(
                new WriteBatch("user", 1, List.of(
                        new WriteRow(T0 + 100, f("uid", "u1", "age", "bad")),
                        new WriteRow(T0 + 200, f("uid", "u2", "age", "also-bad"))))));

        List<AuditEntry> u1 = fx.audit.forEntity("user", "u1", 50);
        assertEquals(1, u1.size());
        AuditEntry e = u1.get(0);
        assertEquals("WRITE_ROW", e.action());
        assertEquals(T0 + 100, e.eventTime());
        assertTrue(e.reason().startsWith(RejectionReason.TYPE_MISMATCH.name()));
        assertEquals(0, e.evidence().get("rowIndex"));

        // filtering the other entity returns only its own row
        List<AuditEntry> u2 = fx.audit.forEntity("user", "u2", 50);
        assertEquals(1, u2.size());
        assertEquals(T0 + 200, u2.get(0).eventTime());
    }
}
