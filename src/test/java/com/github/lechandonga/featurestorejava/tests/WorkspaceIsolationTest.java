package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.error.NotFoundException;
import com.github.lechandonga.featurestorejava.error.WorkspaceNotFoundException;
import com.github.lechandonga.featurestorejava.model.AuditEntry;
import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.RejectionReason;
import com.github.lechandonga.featurestorejava.model.ValueStatus;
import com.github.lechandonga.featurestorejava.model.WorkspaceRequest;
import com.github.lechandonga.featurestorejava.model.WorkspaceUsage;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.feature;
import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static com.github.lechandonga.featurestorejava.model.MissingPolicy.NULL;
import static com.github.lechandonga.featurestorejava.model.ValueType.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspaces isolate definitions, data, corrections, audit and statistics;
 * the default workspace keeps serving calls that name no workspace.
 */
class WorkspaceIsolationTest {

    private static final long T0 = StoreFixture.T0;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static StoreFixture twoWorkspaces() {
        StoreFixture fx = new StoreFixture();
        fx.workspaces.create(new WorkspaceRequest("team-a", null, null, "team A"));
        fx.workspaces.create(new WorkspaceRequest("team-b", null, null, "team B"));
        return fx;
    }

    @Test
    void createListAndInspectWorkspaces() {
        StoreFixture fx = twoWorkspaces();

        List<WorkspaceUsage> all = fx.workspaces.list();
        assertEquals(List.of("default", "team-a", "team-b"),
                all.stream().map(WorkspaceUsage::name).toList());

        WorkspaceUsage a = fx.workspaces.usage("team-a");
        assertEquals(0, a.featureSetCount());
        assertEquals(0, a.pointCount());

        // unknown workspace is distinguishable
        assertThrows(WorkspaceNotFoundException.class, () -> fx.workspaces.usage("nope"));
        assertThrows(WorkspaceNotFoundException.class,
                () -> fx.registry.requireVersion("nope", "user", null));
    }

    @Test
    void sameNameFeatureSetsWithDifferentDefinitionsStayIsolated() {
        StoreFixture fx = twoWorkspaces();

        // same feature set name, different definitions per workspace
        FeatureSetDef aDef = fx.registry.register("team-a",
                new RegisterRequest("user", "uid", null, sampleFeatures(), "A"));
        FeatureSetDef bDef = fx.registry.register("team-b",
                new RegisterRequest("user", "uid", 60_000L,
                        List.of(feature("nickname", STRING, true, null, NULL)), "B"));
        assertEquals(1, aDef.version());
        assertEquals(1, bDef.version());
        assertEquals(2, aDef.features().size());
        assertEquals(1, bDef.features().size());

        // evolve only team-a: versions diverge independently
        FeatureSetDef aV2 = fx.registry.evolve("team-a", "user",
                List.of(feature("age", com.github.lechandonga.featurestorejava.model.ValueType.LONG, true, 0L,
                                com.github.lechandonga.featurestorejava.model.MissingPolicy.DEFAULT),
                        feature("score", com.github.lechandonga.featurestorejava.model.ValueType.DOUBLE, false, null,
                                com.github.lechandonga.featurestorejava.model.MissingPolicy.NULL),
                        feature("extra", STRING, false, null, NULL)), null);
        assertEquals(2, aV2.version());
        assertEquals(1, fx.registry.requireVersion("team-b", "user", null).version());

        // writes go to the addressed workspace only
        fx.writes.submit("team-a", new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u1", "age", 30)))));
        fx.writes.submit("team-b", new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u1", "nickname", "neo")))));

        // team-a read sees team-a's value
        assertEquals(30L, fx.reads.online("team-a", "user", 1, "u1", T0 + 1000)
                .features().get("age").value());
        // team-b read sees team-b's (differently shaped) value
        assertEquals("neo", fx.reads.online("team-b", "user", 1, "u1", T0 + 1000)
                .features().get("nickname").value());
        // team-b has no "age" feature at all; team-a has no "nickname"
        assertFalse(fx.reads.online("team-b", "user", 1, "u1", T0 + 1000)
                .features().containsKey("age"));

        // observability is per workspace
        assertEquals(1, fx.ops.coverage("team-a", "user", 1, T0 + 1000).entityCount());
        assertEquals(1, fx.ops.coverage("team-b", "user", 1, T0 + 1000).entityCount());
        assertEquals(1, fx.workspaces.usage("team-a").pointCount());
        assertEquals(1, fx.workspaces.usage("team-b").pointCount());

        // audit is filterable per workspace
        List<AuditEntry> aAudit = fx.audit.recent("team-a", 100);
        List<AuditEntry> bAudit = fx.audit.recent("team-b", 100);
        assertTrue(aAudit.stream().allMatch(e -> "team-a".equals(e.workspace())));
        assertTrue(bAudit.stream().allMatch(e -> "team-b".equals(e.workspace())));
        assertTrue(aAudit.stream().anyMatch(e -> "WRITE".equals(e.action())));
        assertTrue(bAudit.stream().anyMatch(e -> "WRITE".equals(e.action())));
    }

    @Test
    void defaultWorkspaceServesUnscopedCallsAndPreExistingData() {
        StoreFixture fx = twoWorkspaces();

        // "pre-existing" data: registered and written without any workspace
        fx.registry.register(new RegisterRequest("user", "uid", null, sampleFeatures(), "d"));
        fx.writes.submit(new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u1", "age", 42)))));

        // a new workspace may register the SAME name with a different
        // definition: no false ALREADY_EXISTS against default-workspace data
        FeatureSetDef bDef = fx.registry.register("team-b",
                new RegisterRequest("user", "uid", null,
                        List.of(feature("nickname", STRING, true, null, NULL)), "B"));
        assertEquals(1, bDef.version());

        // unscoped reads still see the default workspace's value
        assertEquals(42L, fx.reads.online("user", 1, "u1", T0 + 1000)
                .features().get("age").value());
        // the new workspace is unaffected and empty
        assertEquals(ValueStatus.MISSING,
                fx.reads.online("team-b", "user", 1, "u1", T0 + 1000)
                        .features().get("nickname").status());
        assertEquals(1, fx.workspaces.usage("default").featureSetCount());
        assertEquals(1, fx.workspaces.usage("team-b").featureSetCount());
    }

    @Test
    void backfillCorrectionsAndIdempotencyArePerWorkspace() {
        StoreFixture fx = twoWorkspaces();
        for (String ws : List.of("team-a", "team-b")) {
            fx.registry.register(ws, new RegisterRequest("user", "uid", null,
                    sampleFeatures(), "d"));
            fx.writes.submit(ws, new WriteBatch("user", 1,
                    List.of(new WriteRow(T0 + 100, f("uid", "u1", "age", 10)))));
        }

        // correct only in team-a
        String aPoint = fx.state.offline("team-a").forFeatureSet("user", 1).get(0).id();
        fx.clock.set(T0 + 5000);
        fx.backfills.backfill("team-a", new BackfillRequest("user", 1, "job-1",
                List.of(new Correction(aPoint, "u1", T0 + 100, f("age", 99), "fix"))));

        // team-a sees the correction; team-b is untouched
        assertEquals(99L, fx.reads.online("team-a", "user", 1, "u1", T0 + 6000)
                .features().get("age").value());
        assertEquals(10L, fx.reads.online("team-b", "user", 1, "u1", T0 + 6000)
                .features().get("age").value());

        // the same correlationId in team-b is NOT a replay of team-a's job:
        // idempotency records are workspace-scoped
        String bPoint = fx.state.offline("team-b").forFeatureSet("user", 1).get(0).id();
        var bResult = fx.backfills.backfill("team-b", new BackfillRequest("user", 1, "job-1",
                List.of(new Correction(bPoint, "u1", T0 + 100, f("age", 77), "fix"))));
        assertEquals(1, bResult.appliedCount());
        assertEquals(77L, fx.reads.online("team-b", "user", 1, "u1", T0 + 6000)
                .features().get("age").value());
    }

    @Test
    void crossWorkspacePointReferenceIsRejectedDistinguishably() {
        StoreFixture fx = twoWorkspaces();
        fx.registry.register("team-a", new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));
        fx.registry.register("team-b", new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));
        fx.writes.submit("team-a", new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u1", "age", 10)))));
        String aPoint = fx.state.offline("team-a").forFeatureSet("user", 1).get(0).id();

        // team-b tries to correct a point owned by team-a
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> fx.backfills.backfill("team-b", new BackfillRequest("user", 1, null,
                        List.of(new Correction(aPoint, "u1", T0 + 100, f("age", 1), "x")))));
        assertEquals(RejectionReason.CROSS_WORKSPACE_ACCESS,
                ex.getRejections().get(0).reason());
        assertEquals("team-a", ex.getRejections().get(0).evidence().get("ownerWorkspace"));

        // a point id that exists nowhere stays a plain not-found
        BatchRejectedException nf = assertThrows(BatchRejectedException.class,
                () -> fx.backfills.backfill("team-b", new BackfillRequest("user", 1, null,
                        List.of(new Correction("no-such-point", "u1", T0 + 100,
                                f("age", 1), "x")))));
        assertEquals(RejectionReason.TARGET_POINT_NOT_FOUND,
                nf.getRejections().get(0).reason());

        // nothing was appended anywhere
        assertEquals(1, fx.workspaces.usage("team-a").pointCount());
        assertEquals(0, fx.workspaces.usage("team-b").pointCount());
    }

    @Test
    void readsInOneWorkspaceNeverSeeAnotherWorkspacesData() {
        StoreFixture fx = twoWorkspaces();
        fx.registry.register("team-a", new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));
        fx.writes.submit("team-a", new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u1", "age", 10)))));

        // same name does not exist in team-b -> 404-style not found, scoped message
        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> fx.reads.online("team-b", "user", null, "u1", T0 + 1000));
        assertTrue(ex.getMessage().contains("team-b"));
        assertNotNull(ex.getMessage());
    }
}
