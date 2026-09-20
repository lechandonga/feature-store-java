package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.RejectionReason;
import com.github.lechandonga.featurestorejava.model.ValueStatus;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write-time schema validation. Every rejected batch must leave zero state
 * behind — no partially visible rows.
 */
class WriteValidationTest {

    private final StoreFixture fx = new StoreFixture();

    /** Build a String -> Object row without Map.of overload inference issues. */
    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static WriteRow row(long eventTime, Map<String, Object> fields) {
        return new WriteRow(eventTime, fields);
    }

    @BeforeEach
    void registerSet() {
        fx.registry.register(new RegisterRequest("user", "uid", null, sampleFeatures(), "d"));
    }

    private BatchRejectedException reject(WriteBatch batch) {
        return assertThrows(BatchRejectedException.class, () -> fx.writes.submit(batch));
    }

    private boolean has(BatchRejectedException ex, RejectionReason reason) {
        return ex.getRejections().stream().anyMatch(r -> r.reason() == reason);
    }

    @Test
    void validBatchIsAcceptedAndReadable() {
        var result = fx.writes.submit(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 30, "score", 0.9)))));
        assertEquals(1, result.acceptedRows());
        var online = fx.reads.online("user", 1, "u1", StoreFixture.T0 + 1000L);
        assertEquals(ValueStatus.PRESENT, online.features().get("age").status());
        assertEquals(30L, online.features().get("age").value());
    }

    @Test
    void missingRequiredFieldRejectsWholeBatch() {
        var ex = reject(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "score", 0.9)),                 // age missing
                row(200, f("uid", "u2", "age", 40, "score", 1.0)))));
        assertTrue(has(ex, RejectionReason.MISSING_REQUIRED_FIELD));
        assertTrue(fx.state.offline().entities("user", 1).isEmpty());
    }

    @Test
    void missingEntityKeyIsRejected() {
        var ex = reject(new WriteBatch("user", 1, List.of(
                row(100, f("age", 30, "score", 0.9)))));
        assertTrue(has(ex, RejectionReason.ROW_MISSING_ENTITY_KEY));
        assertTrue(fx.state.offline().entities("user", 1).isEmpty());
    }

    @Test
    void unknownFieldIsRejected() {
        var ex = reject(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 30, "bogus", 1)))));
        assertTrue(has(ex, RejectionReason.UNKNOWN_FIELD));
        assertTrue(fx.state.offline().entities("user", 1).isEmpty());
    }

    @Test
    void typeMismatchIsRejectedWithActualAndExpectedTypes() {
        var ex = reject(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", "not-a-number")))));
        assertTrue(has(ex, RejectionReason.TYPE_MISMATCH));
        assertEquals("LONG", ex.getRejections().get(0).evidence().get("expected"));
    }

    @Test
    void integerIntoDoubleSlotIsPromoted() {
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 30, "score", 1)))));
        var online = fx.reads.online("user", 1, "u1", StoreFixture.T0 + 1000L);
        assertEquals(1.0, online.features().get("score").value());
    }

    @Test
    void duplicateWithinBatchIsRejected() {
        var ex = reject(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 30)),
                row(100, f("uid", "u1", "age", 30)))));
        assertTrue(has(ex, RejectionReason.DUPLICATE_WITHIN_BATCH));
        assertTrue(fx.state.offline().entities("user", 1).isEmpty());
    }

    @Test
    void reverseOrderWithinBatchIsRejected() {
        var ex = reject(new WriteBatch("user", 1, List.of(
                row(200, f("uid", "u1", "age", 31)),
                row(100, f("uid", "u1", "age", 30)))));
        assertTrue(has(ex, RejectionReason.REVERSE_ORDER_WITHIN_BATCH));
        assertTrue(fx.state.offline().entities("user", 1).isEmpty());
    }

    @Test
    void identicalRewriteIsRejectedAsDuplicateButStateStaysValid() {
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 30)))));
        var ex = reject(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 30)))));
        assertTrue(has(ex, RejectionReason.DUPLICATE_ALREADY_STORED));
        assertEquals(1, fx.state.offline().forFeatureSet("user", 1).size());
    }

    @Test
    void sameTimeDifferentValueMustUseBackfill() {
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 30)))));
        var ex = reject(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 31)))));
        assertTrue(has(ex, RejectionReason.SAME_TIME_DIFFERENT_VALUE));
        var online = fx.reads.online("user", 1, "u1", StoreFixture.T0 + 1000L);
        assertEquals(30L, online.features().get("age").value());
    }

    @Test
    void lateArrivingOlderEventTimeIsAcceptedAndDoesNotRewritePresent() {
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                row(200, f("uid", "u1", "age", 31)))));
        // late data at an older event time — allowed
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 30)))));
        var at300 = fx.reads.online("user", 1, "u1", StoreFixture.T0 + 300L);
        assertEquals(31L, at300.features().get("age").value());
    }

    @Test
    void unknownFeatureSetAndWrongVersionAreRejectedDistinctly() {
        var missing = reject(new WriteBatch("nope", 1, List.of(row(100, f("x", 1)))));
        assertEquals(RejectionReason.FEATURE_SET_NOT_FOUND,
                missing.getRejections().get(0).reason());
        var wrongVersion = reject(new WriteBatch("user", 9,
                List.of(row(100, f("uid", "u1", "age", 30)))));
        assertEquals(RejectionReason.VERSION_MISMATCH,
                wrongVersion.getRejections().get(0).reason());
    }

    @Test
    void optionalFieldMissingResolvesToDeclaredMissingPolicy() {
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                row(100, f("uid", "u1", "age", 30))))); // score omitted, policy NULL
        var online = fx.reads.online("user", 1, "u1", StoreFixture.T0 + 1000L);
        assertEquals(ValueStatus.MISSING, online.features().get("score").status());
    }
}
