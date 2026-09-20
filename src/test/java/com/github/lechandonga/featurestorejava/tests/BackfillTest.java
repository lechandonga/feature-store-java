package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.BackfillResult;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.RejectionReason;
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
 * Backfill validation, immutability of business time and idempotent replay.
 */
class BackfillTest {

    private final StoreFixture fx = new StoreFixture();
    private static final long T0 = StoreFixture.T0;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @BeforeEach
    void setup() {
        fx.registry.register(new RegisterRequest("user", "uid", null, sampleFeatures(), "d"));
    }

    private String writePoint(long eventTime, int age) {
        return fx.writes.submit(new WriteBatch("user", 1,
                List.of(new WriteRow(eventTime, f("uid", "u1", "age", age))))).pointIds().get(0);
    }

    private boolean has(BatchRejectedException ex, RejectionReason reason) {
        return ex.getRejections().stream().anyMatch(r -> r.reason() == reason);
    }

    @Test
    void correctsValueAndSwitchesOnlineChainAtomically() {
        String p = writePoint(T0 + 100, 10);
        fx.clock.set(T0 + 5000);
        BackfillResult result = fx.backfills.backfill(new BackfillRequest("user", 1, "j",
                List.of(new Correction(p, "u1", T0 + 100, f("age", 11), "fixed"))));
        assertEquals(1, result.appliedCount());

        // online chain now exposes exactly one point for that event time, corrected
        var chain = fx.state.online().chain("user", 1, "u1");
        assertEquals(1, chain.size());
        assertEquals(11L, chain.get(0).values().get("age"));
        assertEquals(p, chain.get(0).supersedes());

        // present read sees the correction; past read still sees the original
        var now = fx.reads.offline("user", 1,
                List.of(new EntityAtPoint("u1", T0 + 6000))).get(0);
        assertEquals(11L, now.features().get("age").value());
        var past = fx.reads.offline("user", 1,
                List.of(new EntityAtPoint("u1", T0 + 1000))).get(0);
        assertEquals(10L, past.features().get("age").value());
    }

    @Test
    void unknownTargetPointIsRejectedAndStateUnchanged() {
        long before = fx.state.offline().forFeatureSet("user", 1).size();
        var ex = assertThrows(BatchRejectedException.class,
                () -> fx.backfills.backfill(new BackfillRequest("user", 1, null,
                        List.of(new Correction("nope", "u1", 1, f("age", 1), "x")))));
        assertTrue(has(ex, RejectionReason.TARGET_POINT_NOT_FOUND));
        assertEquals(before, fx.state.offline().forFeatureSet("user", 1).size());
    }

    @Test
    void eventTimeCannotBeRewritten() {
        String p = writePoint(T0 + 100, 10);
        var ex = assertThrows(BatchRejectedException.class,
                () -> fx.backfills.backfill(new BackfillRequest("user", 1, null,
                        List.of(new Correction(p, "u1", T0 + 999, f("age", 11), "x")))));
        assertTrue(has(ex, RejectionReason.EVENT_TIME_IMMUTABLE));
        assertEquals(10L, fx.reads.online("user", 1, "u1", T0 + 6000)
                .features().get("age").value());
    }

    @Test
    void entityMismatchIsRejected() {
        String p = writePoint(T0 + 100, 10);
        var ex = assertThrows(BatchRejectedException.class,
                () -> fx.backfills.backfill(new BackfillRequest("user", 1, null,
                        List.of(new Correction(p, "other", T0 + 100, f("age", 11), "x")))));
        assertTrue(has(ex, RejectionReason.TARGET_POINT_MISMATCH));
    }

    @Test
    void correctingSamePointTwiceInOneBatchIsRejected() {
        String p = writePoint(T0 + 100, 10);
        var ex = assertThrows(BatchRejectedException.class,
                () -> fx.backfills.backfill(new BackfillRequest("user", 1, null, List.of(
                        new Correction(p, "u1", T0 + 100, f("age", 11), "x"),
                        new Correction(p, "u1", T0 + 100, f("age", 12), "x")))));
        assertTrue(has(ex, RejectionReason.DUPLICATE_WITHIN_BATCH));
        // no correction applied at all
        assertEquals(10L, fx.reads.online("user", 1, "u1", T0 + 6000)
                .features().get("age").value());
    }

    @Test
    void invalidCorrectedValuesAreRejectedLikeWrites() {
        String p = writePoint(T0 + 100, 10);
        var ex = assertThrows(BatchRejectedException.class,
                () -> fx.backfills.backfill(new BackfillRequest("user", 1, null,
                        List.of(new Correction(p, "u1", T0 + 100, f("age", "bad"), "x")))));
        assertTrue(has(ex, RejectionReason.TYPE_MISMATCH));
        assertEquals(10L, fx.reads.online("user", 1, "u1", T0 + 6000)
                .features().get("age").value());
    }

    @Test
    void replayingSameCorrelationIdIsIdempotent() {
        String p = writePoint(T0 + 100, 10);
        fx.clock.set(T0 + 5000);
        var req = new BackfillRequest("user", 1, "job-42",
                List.of(new Correction(p, "u1", T0 + 100, f("age", 11), "fixed")));
        BackfillResult first = fx.backfills.backfill(req);
        long pointsAfterFirst = fx.state.offline().forFeatureSet("user", 1).size();
        BackfillResult replay = fx.backfills.backfill(req);
        assertEquals(first.newPointIds(), replay.newPointIds());
        assertEquals(pointsAfterFirst, fx.state.offline().forFeatureSet("user", 1).size());
    }
}
