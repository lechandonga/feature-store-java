package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.WorkspaceCapacityExceededException;
import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.BackfillResult;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.WorkspaceRequest;
import com.github.lechandonga.featurestorejava.model.WorkspaceUsage;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import com.github.lechandonga.featurestorejava.service.AuditLog;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import com.github.lechandonga.featurestorejava.service.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.PersistenceService;
import com.github.lechandonga.featurestorejava.service.ReadService;
import com.github.lechandonga.featurestorejava.service.StoreState;
import com.github.lechandonga.featurestorejava.service.WorkspaceService;
import com.github.lechandonga.featurestorejava.service.WriteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.feature;
import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static com.github.lechandonga.featurestorejava.model.MissingPolicy.NULL;
import static com.github.lechandonga.featurestorejava.model.ValueType.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restart recovery: workspace list, capacity config, per-workspace data and
 * correction chains, and backfill idempotency records all survive a restart.
 * Legacy (pre-workspace) snapshots load into the default workspace.
 */
class WorkspacePersistenceTest {

    private static final long T0 = StoreFixture.T0;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A full service graph over one StoreState, like a fresh process. */
    private record Proc(StoreState state, FeatureRegistry registry, WriteService writes,
                        ReadService reads, BackfillService backfills,
                        WorkspaceService workspaces, PersistenceService persistence,
                        FixedTimeSource clock) {
    }

    private Proc newProc(Path dir) {
        FixedTimeSource clock = new FixedTimeSource(T0);
        AuditLog audit = new AuditLog();
        StoreState state = new StoreState();
        FeatureRegistry registry = new FeatureRegistry(state, audit, clock);
        WriteService writes = new WriteService(state, registry, audit, clock);
        ReadService reads = new ReadService(state, registry, clock);
        BackfillService backfills = new BackfillService(state, registry, audit, clock);
        WorkspaceService workspaces = new WorkspaceService(state, audit, clock);
        PersistenceService persistence = new PersistenceService(state, dir.toString());
        persistence.load();
        return new Proc(state, registry, writes, reads, backfills, workspaces,
                persistence, clock);
    }

    @Test
    void workspacesDataCorrectionsAndIdempotencySurviveRestart(@TempDir Path dir) {
        Proc p1 = newProc(dir);
        p1.workspaces().create(new WorkspaceRequest("team-a", 5L, 100L, "A"));
        p1.workspaces().create(new WorkspaceRequest("team-b", null, null, "B"));

        // default workspace holds "pre-existing" data
        p1.registry().register(new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));
        p1.writes().submit(new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u0", "age", 1)))));

        // team-a: same feature set name, different definition + a correction
        p1.registry().register("team-a", new RegisterRequest("user", "uid", null,
                sampleFeatures(), "d"));
        p1.writes().submit("team-a", new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u1", "age", 10)))));
        String aPoint = p1.state().offline("team-a").forFeatureSet("user", 1).get(0).id();
        p1.clock().set(T0 + 5000);
        BackfillResult job = p1.backfills().backfill("team-a",
                new BackfillRequest("user", 1, "job-1",
                        List.of(new Correction(aPoint, "u1", T0 + 100, f("age", 99), "fix"))));

        // team-b: yet another definition of "user"
        p1.registry().register("team-b", new RegisterRequest("user", "uid", null,
                List.of(feature("nickname", STRING, true, null, NULL)), "d"));
        p1.writes().submit("team-b", new WriteBatch("user", 1,
                List.of(new WriteRow(T0 + 100, f("uid", "u1", "nickname", "neo")))));

        // ---- restart: brand-new service graph over the same directory ----
        Proc p2 = newProc(dir);

        // workspace list and capacity config restored
        assertEquals(List.of("default", "team-a", "team-b"), p2.state().workspaceNames());
        WorkspaceUsage a = p2.workspaces().usage("team-a");
        assertEquals(5L, a.maxFeatureSets());
        assertEquals(100L, a.maxPoints());
        assertEquals(1, a.featureSetCount());
        assertEquals(2, a.pointCount()); // original point + correction

        // per-workspace data and correction relationships restored
        long now = T0 + 9000;
        assertEquals(1L, p2.reads().offline("default", "user", 1,
                List.of(new EntityAtPoint("u0", now))).get(0).features().get("age").value());
        assertEquals(99L, p2.reads().offline("team-a", "user", 1,
                List.of(new EntityAtPoint("u1", now))).get(0).features().get("age").value());
        // past as-of view of team-a is reproducible (pre-correction value)
        assertEquals(10L, p2.reads().offline("team-a", "user", 1,
                List.of(new EntityAtPoint("u1", T0 + 1000))).get(0)
                .features().get("age").value());
        assertEquals("neo", p2.reads().offline("team-b", "user", 1,
                List.of(new EntityAtPoint("u1", now))).get(0)
                .features().get("nickname").value());

        // backfill idempotency restored: replaying job-1 returns the same ids
        // and appends nothing
        BackfillResult replay = p2.backfills().backfill("team-a",
                new BackfillRequest("user", 1, "job-1",
                        List.of(new Correction(aPoint, "u1", T0 + 100, f("age", 123), "fix"))));
        assertEquals(job.newPointIds(), replay.newPointIds());
        assertEquals(2, p2.workspaces().usage("team-a").pointCount());
        assertEquals(99L, p2.reads().offline("team-a", "user", 1,
                List.of(new EntityAtPoint("u1", now))).get(0).features().get("age").value());

        // capacity limits still enforced after restart
        Proc p2c = p2;
        p2c.workspaces().create(new WorkspaceRequest("tiny", 1L, null, "d"));
        p2c.registry().register("tiny", new RegisterRequest("x", "uid", null,
                sampleFeatures(), "d"));
        assertThrows(WorkspaceCapacityExceededException.class,
                () -> p2c.registry().register("tiny", new RegisterRequest("y", "uid", null,
                        sampleFeatures(), "d")));
    }

    @Test
    void legacySingleTenantSnapshotLoadsIntoDefaultWorkspace(@TempDir Path dir)
            throws Exception {
        // write a legacy-format snapshot by hand: {definitions, points}
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("definitions", List.of(Map.of(
                "name", "user", "version", 1, "entityKey", "uid",
                "features", List.of(Map.of(
                        "name", "age", "type", "LONG", "required", true,
                        "defaultValue", 0, "missingPolicy", "DEFAULT",
                        "description", "d")),
                "createdAt", T0)));
        legacy.put("points", List.of(Map.of(
                "id", "p1", "featureSet", "user", "version", 1, "entity", "u1",
                "eventTime", T0 + 100, "ingestTime", T0 + 100,
                "values", Map.of("age", 42), "source", "WRITE")));
        new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValue(dir.resolve("feature-store-snapshot.json").toFile(), legacy);

        Proc p = newProc(dir);

        // legacy data is readable from the default workspace
        assertEquals(List.of("default"), p.state().workspaceNames());
        assertEquals(42L, p.reads().online("user", 1, "u1", T0 + 1000)
                .features().get("age").value());

        // a new workspace may register the same name without a false conflict
        p.workspaces().create(new WorkspaceRequest("team-a", null, null, "A"));
        p.registry().register("team-a", new RegisterRequest("user", "uid", null,
                List.of(feature("nickname", STRING, true, null, NULL)), "d"));
        assertEquals(1, p.registry().requireVersion("team-a", "user", null).version());
        // default workspace data still intact
        assertEquals(42L, p.reads().online("user", 1, "u1", T0 + 1000)
                .features().get("age").value());
    }
}
