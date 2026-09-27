package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.error.CapacityExceededException;
import com.github.lechandonga.featurestorejava.error.WorkspaceConflictException;
import com.github.lechandonga.featurestorejava.error.WorkspaceNotFoundException;
import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.CreateWorkspaceRequest;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.MissingPolicy;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.RejectionReason;
import com.github.lechandonga.featurestorejava.model.ValueType;
import com.github.lechandonga.featurestorejava.model.WorkspaceLimits;
import com.github.lechandonga.featurestorejava.model.WorkspaceUsage;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import com.github.lechandonga.featurestorejava.service.ConsistencyService;
import com.github.lechandonga.featurestorejava.service.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.ObservabilityService;
import com.github.lechandonga.featurestorejava.service.ReadService;
import com.github.lechandonga.featurestorejava.service.WriteService;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace (namespace) isolation:
 * same-named feature sets with different definitions coexist across
 * workspaces; capacity limits reject whole batches without partial changes;
 * cross-workspace point access is rejected distinctly; the default workspace
 * keeps working without any workspace argument.
 */
class WorkspaceIsolationTest {

    private static final long T0 = StoreFixture.T0;
    private static final String TEAM_A = "team-a";
    private static final String TEAM_B = "team-b";

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static FeatureDef feature(String name, ValueType type, boolean required,
                                      Object defaultValue, MissingPolicy policy) {
        return new FeatureDef(name, type, required, defaultValue, policy, name + " feature");
    }

    /** Fully wired services over one shared WorkspaceManager. */
    private record Harness(FixedTimeSource clock, WorkspaceManager workspaces,
                           FeatureRegistry registry, WriteService writes, ReadService reads,
                           BackfillService backfills, ConsistencyService consistency,
                           ObservabilityService ops) {

        static Harness create() {
            FixedTimeSource clock = new FixedTimeSource(T0);
            WorkspaceManager ws = new WorkspaceManager(clock);
            ws.initDefault();
            FeatureRegistry registry = new FeatureRegistry(ws, clock);
            WriteService writes = new WriteService(ws, registry, clock);
            ReadService reads = new ReadService(ws, registry, clock);
            BackfillService backfills = new BackfillService(ws, registry, clock);
            ConsistencyService consistency = new ConsistencyService(reads, registry);
            ObservabilityService ops = new ObservabilityService(ws, registry, reads, clock);
            return new Harness(clock, ws, registry, writes, reads, backfills, consistency, ops);
        }
    }

    @Test
    void sameFeatureSetNameCanBeRegisteredDifferentlyInEachWorkspace() {
        Harness h = Harness.create();
        h.workspaces().create(TEAM_A, null);
        h.workspaces().create(TEAM_B, null);

        // "user" in team-a: LONG age + DOUBLE score
        h.registry().register(TEAM_A, new RegisterRequest("user", "uid", null,
                sampleFeatures(), "team-a user"));
        // "user" in team-b: STRING name, different entity key, no conflict
        List<FeatureDef> teamBFeatures = List.of(feature("name", ValueType.STRING, true,
                null, MissingPolicy.REJECT));
        h.registry().register(TEAM_B, new RegisterRequest("user", "customer_id", null,
                teamBFeatures, "team-b user"));

        h.clock().set(T0);
        h.writes().submit(TEAM_A, new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 10)))));
        h.writes().submit(TEAM_B, new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("customer_id", "c1", "name", "alice")))));

        // each workspace reads only its own value and definition
        assertEquals(10L, h.reads().online(TEAM_A, "user", null, "u1", T0 + 200)
                .features().get("age").value());
        assertEquals("alice", h.reads().online(TEAM_B, "user", null, "c1", T0 + 200)
                .features().get("name").value());

        // no leakage in either direction: c1 is unknown in team-a, so its age
        // is DEFAULTED (never team-b's "alice"-row data of any shape)
        var leaked = h.reads().online(TEAM_A, "user", null, "c1", T0 + 200)
                .features().get("age");
        assertEquals(com.github.lechandonga.featurestorejava.model.ValueStatus.DEFAULTED,
                leaked.status());
        var teamAReport = h.ops().coverage(TEAM_A, "user", null, T0 + 200);
        assertEquals(1, teamAReport.entityCount());
        assertEquals(2, teamAReport.featureCount());
        var teamBReport = h.ops().coverage(TEAM_B, "user", null, T0 + 200);
        assertEquals(1, teamBReport.entityCount());
        assertEquals(1, teamBReport.featureCount());
    }

    @Test
    void missingWorkspaceIsRejectedDistinctly() {
        Harness h = Harness.create();
        WorkspaceNotFoundException ex = assertThrows(WorkspaceNotFoundException.class,
                () -> h.registry().register("nope",
                        new RegisterRequest("x", "k", null, sampleFeatures(), "d")));
        assertEquals("nope", ex.getWorkspace());
        assertThrows(WorkspaceNotFoundException.class,
                () -> h.reads().online("nope", "user", null, "u1", T0));
    }

    @Test
    void duplicateWorkspaceCreationConflictsAndLeavesStateIntact() {
        Harness h = Harness.create();
        h.workspaces().create(TEAM_A, new WorkspaceLimits(1, 5L));
        assertThrows(WorkspaceConflictException.class,
                () -> h.workspaces().create(TEAM_A, null));
        // original limits survived the rejected creation
        WorkspaceUsage usage = h.workspaces().usage(TEAM_A);
        assertEquals(1, usage.limits().maxFeatureSets());
        assertEquals(5L, usage.limits().maxPoints());
        assertEquals(2, h.workspaces().list().size()); // default + team-a
    }

    @Test
    void featureSetCapacityRejectsRegistrationAndKeepsExistingSets() {
        Harness h = Harness.create();
        h.workspaces().create(TEAM_A, new WorkspaceLimits(2, null));
        h.registry().register(TEAM_A, new RegisterRequest("fs1", "k", null,
                sampleFeatures(), "d"));
        h.registry().register(TEAM_A, new RegisterRequest("fs2", "k", null,
                sampleFeatures(), "d"));

        CapacityExceededException ex = assertThrows(CapacityExceededException.class,
                () -> h.registry().register(TEAM_A, new RegisterRequest("fs3", "k", null,
                        sampleFeatures(), "d")));
        assertEquals("FEATURE_SETS", ex.getDimension());
        assertEquals("fs3", ex.getFeatureSet());
        assertEquals(TEAM_A, ex.getWorkspace());
        assertEquals(2, ex.getLimit());
        assertEquals(2, ex.getCurrentUsage());
        assertEquals(1, ex.getRequested());
        // nothing partially created
        assertEquals(2, h.workspaces().usage(TEAM_A).featureSets());
        // the other workspace is unaffected and can register freely
        h.workspaces().create(TEAM_B, null);
        h.registry().register(TEAM_B, new RegisterRequest("fs3", "k", null,
                sampleFeatures(), "d"));
    }

    @Test
    void pointsCapacityRejectsWholeBatchAndAppliesNothing() throws Exception {
        Harness h = Harness.create();
        // limit 2 points total
        h.workspaces().create(TEAM_A, new WorkspaceLimits(null, 2L));
        h.registry().register(TEAM_A, new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));

        h.writes().submit(TEAM_A, new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 1)),
                new WriteRow(T0 + 200, f("uid", "u2", "age", 2)))));
        WorkspaceUsage before = h.workspaces().usage(TEAM_A);
        assertEquals(2, before.points());

        // a 1-row batch already exceeds -> rejected entirely (2 + 1 > 2)
        CapacityExceededException ex = assertThrows(CapacityExceededException.class,
                () -> h.writes().submit(TEAM_A, new WriteBatch("user", 1, List.of(
                        new WriteRow(T0 + 300, f("uid", "u3", "age", 3))))));
        assertEquals("POINTS", ex.getDimension());
        assertEquals(2, ex.getLimit());
        assertEquals(2, ex.getCurrentUsage());
        assertEquals(1, ex.getRequested());
        assertEquals("user", ex.getFeatureSet());
        assertEquals("u3", ex.getContext().get("entity"));

        // state is byte-for-byte unchanged: still exactly 2 points, u3 has no data
        assertEquals(2, h.workspaces().usage(TEAM_A).points());
        assertEquals(com.github.lechandonga.featurestorejava.model.ValueStatus.DEFAULTED,
                h.reads().online(TEAM_A, "user", null, "u3", T0 + 400)
                        .features().get("age").status());
        assertEquals(1L, h.reads().online(TEAM_A, "user", null, "u1", T0 + 400)
                .features().get("age").value());

        // a batch of 2 that fits only partially is STILL rejected as a whole
        assertThrows(CapacityExceededException.class,
                () -> h.writes().submit(TEAM_A, new WriteBatch("user", 1, List.of(
                        new WriteRow(T0 + 300, f("uid", "u3", "age", 3)),
                        new WriteRow(T0 + 400, f("uid", "u4", "age", 4))))));
        assertEquals(2, h.workspaces().usage(TEAM_A).points());
    }

    @Test
    void backfillAcrossWorkspacesIsRejectedWithOwnerNamed() {
        Harness h = Harness.create();
        h.workspaces().create(TEAM_A, null);
        h.workspaces().create(TEAM_B, null);
        h.registry().register(TEAM_A, new RegisterRequest("user", "uid", null,
                sampleFeatures(), "a"));
        h.registry().register(TEAM_B, new RegisterRequest("user", "uid", null,
                sampleFeatures(), "b"));
        h.clock().set(T0);
        var written = h.writes().submit(TEAM_A, new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 1)))));
        String pointId = written.pointIds().get(0);

        // team-b tries to correct a point owned by team-a
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> h.backfills().backfill(TEAM_B, new BackfillRequest("user", 1, null,
                        List.of(new Correction(pointId, "u1", T0 + 100,
                                f("age", 2), "intrusion")))));
        assertEquals(TEAM_B, ex.getWorkspace());
        assertEquals(RejectionReason.CROSS_WORKSPACE_ACCESS,
                ex.getRejections().get(0).reason());
        assertEquals(TEAM_A, ex.getRejections().get(0).evidence().get("ownerWorkspace"));
        assertEquals(TEAM_B, ex.getRejections().get(0).evidence().get("requestedWorkspace"));

        // unknown point id in that workspace -> TARGET_POINT_NOT_FOUND, not cross-access
        BatchRejectedException missing = assertThrows(BatchRejectedException.class,
                () -> h.backfills().backfill(TEAM_B, new BackfillRequest("user", 1, null,
                        List.of(new Correction("does-not-exist", "u1", T0 + 100,
                                f("age", 2), "x")))));
        assertEquals(RejectionReason.TARGET_POINT_NOT_FOUND,
                missing.getRejections().get(0).reason());

        // same-workspace backfill still works and team-a data is unchanged by the attempt
        h.clock().set(T0 + 9000);
        h.backfills().backfill(TEAM_A, new BackfillRequest("user", 1, "j1",
                List.of(new Correction(pointId, "u1", T0 + 100, f("age", 7), "fix"))));
        assertEquals(7L, h.reads().online(TEAM_A, "user", null, "u1", T0 + 9100)
                .features().get("age").value());
    }

    @Test
    void backfillOverPointsCapacityIsRejectedWholesale() {
        Harness h = Harness.create();
        h.workspaces().create(TEAM_A, new WorkspaceLimits(null, 1L));
        h.registry().register(TEAM_A, new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));
        var written = h.writes().submit(TEAM_A, new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 1)))));
        assertEquals(1, h.workspaces().usage(TEAM_A).points());

        // a correction appends one point -> over the limit -> whole job rejected
        assertThrows(CapacityExceededException.class,
                () -> h.backfills().backfill(TEAM_A, new BackfillRequest("user", 1, "j1",
                        List.of(new Correction(written.pointIds().get(0), "u1", T0 + 100,
                                f("age", 2), "fix")))));
        assertEquals(1, h.workspaces().usage(TEAM_A).points());
        // the value was not corrected
        assertEquals(1L, h.reads().online(TEAM_A, "user", null, "u1", T0 + 9999)
                .features().get("age").value());
        assertFalse(h.workspaces().requireState(TEAM_A).hasCorrelation("j1"));
    }

    @Test
    void defaultWorkspaceKeepsLegacyNoArgumentCallShape() {
        Harness h = Harness.create();
        // all calls omit workspace -> default workspace
        h.registry().register(new RegisterRequest("user", "uid", null, sampleFeatures(), "d"));
        h.clock().set(T0);
        h.writes().submit(new WriteBatch("user", 1, List.of(
                new WriteRow(T0 + 100, f("uid", "u1", "age", 5)))));
        assertEquals(5L, h.reads().online("user", null, "u1", T0 + 200)
                .features().get("age").value());
        // explicit default header/name selects the same workspace
        assertEquals(5L, h.reads()
                .online(WorkspaceManager.DEFAULT_WORKSPACE, "user", null, "u1", T0 + 200)
                .features().get("age").value());
        assertEquals(1, h.workspaces().usage(WorkspaceManager.DEFAULT_WORKSPACE).points());

        // a same-named set in another workspace does NOT collide with default
        h.workspaces().create(TEAM_A, null);
        h.registry().register(TEAM_A, new RegisterRequest("user", "uid", null,
                sampleFeatures(), "a"));
        // reading the default still sees the original point
        assertEquals(5L, h.reads().online("user", null, "u1", T0 + 200)
                .features().get("age").value());
        // the new workspace's same-named set has no data and does not 409
        assertTrue(h.reads().offline(TEAM_A, "user", 1,
                List.of(new EntityAtPoint("u1", T0 + 200))).get(0)
                .features().get("age").status().name().equals("DEFAULTED"));
    }
}
