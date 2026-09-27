package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.WorkspaceCapacityExceededException;
import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.WorkspaceRequest;
import com.github.lechandonga.featurestorejava.model.WorkspaceUsage;
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
 * Per-workspace capacity limits: feature set count and total stored points.
 * Overflow rejects the whole register/write/backfill atomically — existing
 * data and versions are never partially modified — and other workspaces are
 * unaffected.
 */
class WorkspaceCapacityTest {

    private static final long T0 = StoreFixture.T0;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void featureSetLimitRejectsNewRegistrationsAtomically() {
        StoreFixture fx = new StoreFixture();
        fx.workspaces.create(new WorkspaceRequest("capped", 2L, null, "d"));

        fx.registry.register("capped", new RegisterRequest("s1", "uid", null,
                sampleFeatures(), "d"));
        fx.registry.register("capped", new RegisterRequest("s2", "uid", null,
                sampleFeatures(), "d"));

        WorkspaceCapacityExceededException ex = assertThrows(
                WorkspaceCapacityExceededException.class,
                () -> fx.registry.register("capped", new RegisterRequest("s3", "uid", null,
                        sampleFeatures(), "d")));
        assertEquals("featureSets", ex.getDimension());
        assertEquals(2L, ex.getLimit());
        assertEquals(2L, ex.getCurrent());
        assertEquals("s3", ex.getFeatureSet());
        assertEquals("capped", ex.getWorkspace());

        // nothing partial: s3 has no version, existing sets untouched
        assertEquals(2, fx.workspaces.usage("capped").featureSetCount());
        assertThrows(com.github.lechandonga.featurestorejava.error.NotFoundException.class,
                () -> fx.registry.requireVersion("capped", "s3", null));
        assertEquals(1, fx.registry.requireVersion("capped", "s1", null).version());

        // evolving an EXISTING set does not consume new capacity
        fx.registry.evolve("capped", "s1",
                java.util.List.of(
                        StoreFixture.feature("age",
                                com.github.lechandonga.featurestorejava.model.ValueType.LONG,
                                true, 0L,
                                com.github.lechandonga.featurestorejava.model.MissingPolicy.DEFAULT),
                        StoreFixture.feature("score",
                                com.github.lechandonga.featurestorejava.model.ValueType.DOUBLE,
                                false, null,
                                com.github.lechandonga.featurestorejava.model.MissingPolicy.NULL),
                        StoreFixture.feature("extra",
                                com.github.lechandonga.featurestorejava.model.ValueType.STRING,
                                false, null,
                                com.github.lechandonga.featurestorejava.model.MissingPolicy.NULL)),
                null);
        assertEquals(2, fx.registry.requireVersion("capped", "s1", null).version());

        // other workspaces (and default) are not constrained by "capped"
        fx.registry.register(new RegisterRequest("s3", "uid", null, sampleFeatures(), "d"));
        assertEquals(1, fx.workspaces.usage("default").featureSetCount());
    }

    @Test
    void pointLimitRejectsWholeWriteBatchAndLeavesStateUnchanged() {
        StoreFixture fx = new StoreFixture();
        fx.workspaces.create(new WorkspaceRequest("capped", null, 3L, "d"));
        fx.registry.register("capped", new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));

        // two points fit
        fx.writes.submit("capped", new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 1)),
                new WriteRow(T0 + 200, f("uid", "u1", "age", 2)))));
        assertEquals(2, fx.workspaces.usage("capped").pointCount());

        // a 2-row batch would land at 4 > 3: rejected as a whole
        WorkspaceCapacityExceededException ex = assertThrows(
                WorkspaceCapacityExceededException.class,
                () -> fx.writes.submit("capped", new WriteBatch("user", 1, List.of(
                        new WriteRow(T0 + 300, f("uid", "u1", "age", 3)),
                        new WriteRow(T0 + 400, f("uid", "u2", "age", 4))))));
        assertEquals("points", ex.getDimension());
        assertEquals(3L, ex.getLimit());
        assertEquals(2L, ex.getCurrent());
        assertEquals(2L, ex.getRequested());
        assertEquals("user", ex.getFeatureSet());

        // state unchanged: still 2 points, no partial row visible
        assertEquals(2, fx.workspaces.usage("capped").pointCount());
        assertEquals(2L, fx.reads.online("capped", "user", 1, "u1", T0 + 1000)
                .features().get("age").value());
        assertEquals(com.github.lechandonga.featurestorejava.model.ValueStatus.DEFAULTED,
                fx.reads.online("capped", "user", 1, "u2", T0 + 1000)
                        .features().get("age").status());

        // a batch that fits exactly is still accepted
        fx.writes.submit("capped", new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 300, f("uid", "u1", "age", 3)))));
        assertEquals(3, fx.workspaces.usage("capped").pointCount());

        // capacity rejection is audited with the deciding evidence
        assertTrue(fx.audit.recent("capped", 50).stream().anyMatch(e ->
                "WRITE".equals(e.action()) && "REJECTED".equals(e.decision())
                        && e.reason().contains("capacity")));
    }

    @Test
    void pointLimitRejectsWholeBackfillAndLeavesCorrectionsUnchanged() {
        StoreFixture fx = new StoreFixture();
        fx.workspaces.create(new WorkspaceRequest("capped", null, 3L, "d"));
        fx.registry.register("capped", new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));
        fx.writes.submit("capped", new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 1)),
                new WriteRow(T0 + 200, f("uid", "u1", "age", 2)))));
        List<com.github.lechandonga.featurestorejava.model.StoredPoint> points =
                fx.state.offline("capped").forFeatureSet("user", 1);

        // correcting both points appends 2 -> 4 > 3: whole backfill rejected
        fx.clock.set(T0 + 5000);
        WorkspaceCapacityExceededException ex = assertThrows(
                WorkspaceCapacityExceededException.class,
                () -> fx.backfills.backfill("capped", new BackfillRequest("user", 1, "job-1",
                        List.of(
                                new Correction(points.get(0).id(), "u1", T0 + 100,
                                        f("age", 11), "fix"),
                                new Correction(points.get(1).id(), "u1", T0 + 200,
                                        f("age", 22), "fix")))));
        assertEquals("points", ex.getDimension());

        // no correction appended; present read still serves the old values
        assertEquals(2, fx.workspaces.usage("capped").pointCount());
        assertEquals(2L, fx.reads.online("capped", "user", 1, "u1", T0 + 9000)
                .features().get("age").value());

        // a single correction fits exactly (2 + 1 = 3)
        fx.backfills.backfill("capped", new BackfillRequest("user", 1, "job-2",
                List.of(new Correction(points.get(1).id(), "u1", T0 + 200,
                        f("age", 22), "fix"))));
        assertEquals(3, fx.workspaces.usage("capped").pointCount());
        assertEquals(22L, fx.reads.online("capped", "user", 1, "u1", T0 + 9000)
                .features().get("age").value());
    }

    @Test
    void usageReportsLimitsAndCurrentConsumption() {
        StoreFixture fx = new StoreFixture();
        fx.workspaces.create(new WorkspaceRequest("metered", 5L, 10L, "d"));
        fx.registry.register("metered", new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));
        fx.writes.submit("metered", new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u1", "age", 1)))));

        WorkspaceUsage u = fx.workspaces.usage("metered");
        assertEquals(1, u.featureSetCount());
        assertEquals(5L, u.maxFeatureSets());
        assertEquals(1, u.pointCount());
        assertEquals(10L, u.maxPoints());

        // unlimited workspace reports null limits
        fx.workspaces.create(new WorkspaceRequest("open", null, null, "d"));
        WorkspaceUsage open = fx.workspaces.usage("open");
        assertEquals(null, open.maxFeatureSets());
        assertEquals(null, open.maxPoints());
    }
}
