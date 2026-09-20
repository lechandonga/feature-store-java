package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.model.ConsistencyReport;
import com.github.lechandonga.featurestorejava.model.CoverageReport;
import com.github.lechandonga.featurestorejava.model.DifferenceType;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.FreshnessReport;
import com.github.lechandonga.featurestorejava.model.MissingPolicy;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.ValueStatus;
import com.github.lechandonga.featurestorejava.model.ValueType;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.feature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Online/offline equivalence reporting plus freshness and coverage observation.
 * Staleness is always reported, never silently replaced by a default.
 */
class ConsistencyObservabilityTest {

    private static final long T0 = StoreFixture.T0;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void consistencyReportIsEquivalentForSharedSnapshotReads() {
        StoreFixture fx = new StoreFixture();
        fx.registry.register(new RegisterRequest("user", "uid", null,
                StoreFixture.sampleFeatures(), "d"));
        fx.clock.set(T0);
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 10, "score", 0.5)),
                new WriteRow(T0 + 200, f("uid", "u2", "age", 20)))));
        ConsistencyReport report = fx.consistency.check("user", 1, List.of(
                new EntityAtPoint("u1", T0 + 1000),
                new EntityAtPoint("u2", T0 + 1000)));
        assertTrue(report.equivalent());
        assertEquals(2, report.targetsChecked());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void onlineTtlExpiryIsReportedAndNeverSilentlyDefaulted() {
        StoreFixture fx = new StoreFixture();
        List<FeatureDef> feats = List.of(
                feature("age", ValueType.LONG, true, 0L, MissingPolicy.DEFAULT));
        fx.registry.register(new RegisterRequest("user", "uid", 1000L, feats, "d"));
        fx.clock.set(T0);
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 10)))));

        // within TTL online -> PRESENT
        var fresh = fx.reads.online("user", 1, "u1", T0 + 500);
        assertEquals(ValueStatus.PRESENT, fresh.features().get("age").status());

        // beyond TTL online -> EXPIRED, original value retained, no silent default
        var stale = fx.reads.online("user", 1, "u1", T0 + 5000);
        assertEquals(ValueStatus.EXPIRED, stale.features().get("age").status());
        assertEquals(10L, stale.features().get("age").value());

        // freshness report names the stale entity explicitly
        FreshnessReport fr = fx.ops.freshness("user", 1, T0 + 5000);
        assertFalse(fr.fresh());
        assertEquals(1, fr.staleEntities().size());
        assertEquals("u1", fr.staleEntities().get(0).entity());

        // offline at a training as-of still returns the value valid then, and
        // the expected TTL status difference is classified, not swallowed
        ConsistencyReport report = fx.consistency.check("user", 1, List.of(
                new EntityAtPoint("u1", T0 + 5000)));
        assertEquals(1, report.issues().size());
        assertEquals(DifferenceType.STATUS_MISMATCH, report.issues().get(0).type());
    }

    @Test
    void coverageCountsOnlyPresentCells() {
        StoreFixture fx = new StoreFixture();
        fx.registry.register(new RegisterRequest("user", "uid", null,
                StoreFixture.sampleFeatures(), "d"));
        fx.clock.set(T0);
        // u1 has age only (score missing), u2 has both
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 10)),
                new WriteRow(T0 + 200, f("uid", "u2", "age", 20, "score", 1.0)))));
        CoverageReport report = fx.ops.coverage("user", 1, T0 + 1000);
        assertEquals(2, report.entityCount());
        assertEquals(2, report.featureCount());
        assertEquals(4, report.totalCells());
        assertEquals(3, report.presentCells());
        assertEquals(0.75, report.coverageRatio(), 1e-9);
    }

    @Test
    void rejectMissingPolicySurfacesRejectedStatusInsteadOfDefault() {
        StoreFixture fx = new StoreFixture();
        List<FeatureDef> feats = List.of(
                feature("flag", ValueType.BOOLEAN, false, null, MissingPolicy.REJECT));
        fx.registry.register(new RegisterRequest("user", "uid", null, feats, "d"));
        fx.clock.set(T0);
        fx.writes.submit(new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1")))));
        var online = fx.reads.online("user", 1, "u1", T0 + 1000);
        assertEquals(ValueStatus.REJECTED, online.features().get("flag").status());
    }
}
