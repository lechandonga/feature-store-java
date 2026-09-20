package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import com.github.lechandonga.featurestorejava.service.AuditLog;
import com.github.lechandonga.featurestorejava.service.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.ReadService;
import com.github.lechandonga.featurestorejava.service.StoreState;
import com.github.lechandonga.featurestorejava.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot saved by one store instance is rebuilt by a fresh instance and
 * yields identical point-in-time reads, including backfill corrections.
 */
class PersistenceRoundTripTest {

    private static final long T0 = StoreFixture.T0;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private record Services(StoreState state, FeatureRegistry registry, ReadService reads,
                            FixedTimeSource clock, AuditLog audit) {
    }

    private Services newStore() {
        FixedTimeSource clock = new FixedTimeSource(T0);
        AuditLog audit = new AuditLog();
        StoreState state = new StoreState();
        FeatureRegistry registry = new FeatureRegistry(state, audit, clock);
        ReadService reads = new ReadService(state, registry, clock);
        return new Services(state, registry, reads, clock, audit);
    }

    @Test
    void definitionsAndPointsSurviveRestartIncludingCorrections(@TempDir Path dir) {
        Services s1 = newStore();
        s1.registry().register(new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));
        s1.clock().set(T0);
        // write a point
        var w = writeService(s1).submit(new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u1", "age", 10)))));
        // correct it later
        s1.clock().set(T0 + 9000);
        backfillService(s1).backfill(new com.github.lechandonga.featurestorejava.model.BackfillRequest(
                "user", 1, "j1", List.of(
                new com.github.lechandonga.featurestorejava.model.Correction(
                        w.pointIds().get(0), "u1", T0 + 100, f("age", 11), "fix"))));

        List<FeatureSetDef> defs = s1.state().registry().allLatest();
        List<StoredPoint> points = s1.state().offline().forFeatureSet("user", 1);
        new SnapshotStore(dir).save(defs, points);

        // fresh process: empty stores, load snapshot
        Services s2 = newStore();
        SnapshotStore.Snapshot snap = new SnapshotStore(dir).load();
        s2.state().bootstrap(snap.definitions(), snap.points());

        // present as-of sees the corrected value; past as-of is reproducible
        long now = T0 + 10000;
        assertEquals(11L, (long) s2.reads().offline("user", 1,
                List.of(new EntityAtPoint("u1", now))).get(0)
                .features().get("age").value());
        assertEquals(10L, (long) s2.reads().offline("user", 1,
                List.of(new EntityAtPoint("u1", T0 + 1000))).get(0)
                .features().get("age").value());
        // online chain rebuilt: exactly one visible point at the event time
        assertEquals(1, s2.state().online().chain("user", 1, "u1").size());
        assertEquals(11L, s2.state().online().chain("user", 1, "u1").get(0)
                .values().get("age"));
        assertTrue(s2.state().online().chain("user", 1, "u1").get(0).supersedes()
                .equals(w.pointIds().get(0)));
    }

    private com.github.lechandonga.featurestorejava.service.WriteService writeService(Services s) {
        return new com.github.lechandonga.featurestorejava.service.WriteService(
                s.state(), s.registry(), s.audit(), s.clock());
    }

    private com.github.lechandonga.featurestorejava.service.BackfillService backfillService(Services s) {
        return new com.github.lechandonga.featurestorejava.service.BackfillService(
                s.state(), s.registry(), s.audit(), s.clock());
    }
}
