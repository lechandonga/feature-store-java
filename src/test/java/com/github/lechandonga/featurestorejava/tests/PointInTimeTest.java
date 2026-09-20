package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.OfflineResult;
import com.github.lechandonga.featurestorejava.model.OnlineResult;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.ValueStatus;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import com.github.lechandonga.featurestorejava.model.WriteResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Point-in-time correctness on a single controlled timeline.
 *
 * <pre>
 * ingest clock        business event time      action / value
 * ----------------------------------------------------------------
 * T0                  T0 + 1000                W1   age=10
 * T0 + 5000           T0 + 1500                W2   age=20 (late)
 * T0 + 10000          T0 + 1500                B    age=21 (corrects W2)
 * T0 + 20000          T0 + 100000              F    future event
 * </pre>
 */
class PointInTimeTest {

    private final StoreFixture fx = new StoreFixture();
    private static final long T0 = StoreFixture.T0;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private WriteResult writeAt(long clock, long eventTime, Object age) {
        fx.clock.set(clock);
        return fx.writes.submit(new WriteBatch("user", 1,
                List.of(new WriteRow(eventTime, f("uid", "u1", "age", age)))));
    }

    private long age(OfflineResult r) {
        return (long) r.features().get("age").value();
    }

    private void setup() {
        fx.registry.register(new RegisterRequest("user", "uid", null, sampleFeatures(), "d"));
    }

    @Test
    void futureDatedEventStaysInvisibleUntilItsEventTime() {
        setup();
        writeAt(T0, T0 + 1000, 10);
        fx.clock.set(T0 + 20000);
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100000, f("uid", "u1", "age", 99)))));
        // long after ingest but before the future business time: the future
        // point is invisible, the older age=10 remains the latest visible value
        var at50k = fx.reads.offline("user", 1,
                List.of(new EntityAtPoint("u1", T0 + 50000))).get(0);
        assertEquals(10L, age(at50k));
        assertEquals(T0 + 1000, at50k.features().get("age").eventTime());
        // once the business time is reached the future point becomes visible
        var at100001 = fx.reads.offline("user", 1,
                List.of(new EntityAtPoint("u1", T0 + 100001))).get(0);
        assertEquals(99L, age(at100001));
    }

    @Test
    void nothingIsVisibleBeforeAnyIngestEvenWithADeclaredDefault() {
        setup();
        writeAt(T0, T0 + 1000, 10);
        var before = fx.reads.offline("user", 1,
                List.of(new EntityAtPoint("u1", T0 - 1))).get(0);
        assertEquals(ValueStatus.DEFAULTED, before.features().get("age").status());
    }

    @Test
    void lateArrivingDataDoesNotLeakIntoPastTrainingSnapshots() {
        setup();
        writeAt(T0, T0 + 1000, 10);
        // training snapshot taken at T0+3000 sees age=10
        assertEquals(10L, age(snapshot(T0 + 3000)));
        // late write arrives at T0+5000 with an older event time T0+1500
        writeAt(T0 + 5000, T0 + 1500, 20);
        // the SAME past snapshot point must remain reproducibly age=10
        assertEquals(10L, age(snapshot(T0 + 3000)));
        // present now reflects the newer business event
        assertEquals(20L, age(snapshot(T0 + 6000)));
    }

    @Test
    void backfillCorrectionKeepsPastViewsReproducibleAndUpdatesPresent() {
        setup();
        writeAt(T0, T0 + 1000, 10);
        String w2 = writeAt(T0 + 5000, T0 + 1500, 20).pointIds().get(0);
        assertEquals(20L, age(snapshot(T0 + 6000)));

        // recompute corrects W2's value; business time stays T0+1500
        fx.clock.set(T0 + 10000);
        fx.backfills.backfill(new BackfillRequest("user", 1, "job-1", List.of(
                new Correction(w2, "u1", T0 + 1500, f("age", 21), "source restated"))));

        // past as-of view is byte-for-byte reproducible: still the pre-correction 20
        assertEquals(20L, age(snapshot(T0 + 6000)));
        // present uses the corrected value and proves provenance changed
        OfflineResult now = snapshot(T0 + 11000);
        assertEquals(21L, age(now));
        assertNotEquals(w2, now.features().get("age").pointId());
        // correction visibility is explainable: the chosen point supersedes w2
        var chosen = fx.state.offline().getPoint(now.features().get("age").pointId());
        assertEquals(w2, chosen.supersedes());
        assertEquals("BACKFILL", chosen.source());
    }

    @Test
    void onlineAndOfflineAreEquivalentAtSameVersionEntityAndTime() {
        setup();
        writeAt(T0, T0 + 1000, 10);
        writeAt(T0 + 5000, T0 + 1500, 20);
        long[] asOfs = {T0 - 1, T0 + 1200, T0 + 3000, T0 + 6000};
        for (long asOf : asOfs) {
            OnlineResult online = fx.reads.online("user", 1, "u1", asOf);
            OfflineResult offline = fx.reads.offline("user", 1,
                    List.of(new EntityAtPoint("u1", asOf))).get(0);
            assertEquals(offline.features(), online.features(),
                    "divergence at asOf " + asOf);
        }
    }

    @Test
    void historicalTrainingSnapshotForMultipleEntitiesCannotLeak() {
        setup();
        writeAt(T0, T0 + 1000, 10);
        // another entity with a late value that must not leak before T0+5000
        fx.clock.set(T0 + 5000);
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 1200, f("uid", "u2", "age", 77)))));

        List<OfflineResult> rows = fx.reads.offline("user", 1, List.of(
                new EntityAtPoint("u1", T0 + 3000),
                new EntityAtPoint("u2", T0 + 3000),
                new EntityAtPoint("u2", T0 + 6000)));
        assertEquals(10L, age(rows.get(0)));
        assertEquals(ValueStatus.DEFAULTED, rows.get(1).features().get("age").status());
        assertEquals(77L, age(rows.get(2)));
    }

    private OfflineResult snapshot(long asOf) {
        return fx.reads.offline("user", 1,
                List.of(new EntityAtPoint("u1", asOf))).get(0);
    }
}
