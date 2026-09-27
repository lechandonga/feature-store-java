package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.BackfillResult;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.WorkspaceLimits;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import com.github.lechandonga.featurestorejava.service.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.PersistenceService;
import com.github.lechandonga.featurestorejava.service.ReadService;
import com.github.lechandonga.featurestorejava.service.WriteService;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After a restart the workspace list, capacity limits, every workspace's
 * definitions/points/corrections and the backfill idempotency results are
 * restored. Legacy v1 snapshots (pre-workspace upgrade) keep being readable in
 * the default workspace and never collide with same-named sets elsewhere.
 */
class WorkspaceRecoveryTest {

    private static final long T0 = StoreFixture.T0;

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A "new process": brand-new manager + persistence pointed at {@code dir}. */
    private record Process(FixedTimeSource time, WorkspaceManager manager,
                           FeatureRegistry registry, WriteService writes,
                           ReadService reads, BackfillService backfills,
                           PersistenceService persistence) {
        static Process start(Path dir) {
            FixedTimeSource time = new FixedTimeSource(T0);
            WorkspaceManager manager = new WorkspaceManager(time);
            manager.initDefault();
            PersistenceService persistence = new PersistenceService(manager, dir.toString());
            persistence.startup(); // @PostConstruct equivalent
            FeatureRegistry registry = new FeatureRegistry(manager, time);
            WriteService writes = new WriteService(manager, registry, time);
            ReadService reads = new ReadService(manager, registry, time);
            BackfillService backfills = new BackfillService(manager, registry, time);
            return new Process(time, manager, registry, writes, reads, backfills, persistence);
        }
    }

    @Test
    void workspaceListLimitsDataCorrectionsAndIdempotencySurviveRestart(@TempDir Path dir) {
        Process p1 = Process.start(dir);
        p1.time().set(T0);
        // two workspaces with different capacity limits
        p1.manager().create("alpha", new WorkspaceLimits(3, 1_000L));
        p1.manager().create("beta", new WorkspaceLimits(5, 500L));
        p1.registry().register("alpha", new RegisterRequest("user", "uid", null,
                sampleFeatures(), "a"));
        p1.registry().register("beta", new RegisterRequest("user", "uid", null,
                sampleFeatures(), "b"));

        var w1 = p1.writes().submit("alpha", new com.github.lechandonga.featurestorejava.model.WriteBatch(
                "user", 1, List.of(new com.github.lechandonga.featurestorejava.model.WriteRow(
                        T0 + 100, f("uid", "u1", "age", 10)))));
        p1.time().set(T0 + 9000);
        p1.backfills().backfill("alpha", new BackfillRequest("user", 1, "job-42",
                List.of(new Correction(w1.pointIds().get(0), "u1", T0 + 100,
                        f("age", 11), "fix"))));
        p1.writes().submit("beta", new com.github.lechandonga.featurestorejava.model.WriteBatch(
                "user", 1, List.of(new com.github.lechandonga.featurestorejava.model.WriteRow(
                        T0 + 100, f("uid", "u1", "age", 20)))));

        // ---- restart ----
        Process p2 = Process.start(dir);

        // workspace list and limits restored
        var names = p2.manager().list().stream().map(w -> w.name()).sorted().toList();
        assertEquals(List.of("alpha", "beta", "default"), names);
        assertEquals(3, p2.manager().require("alpha").info().limits().maxFeatureSets());
        assertEquals(1_000L, p2.manager().require("alpha").info().limits().maxPoints());
        assertEquals(500L, p2.manager().require("beta").info().limits().maxPoints());

        // data isolated: corrected value in alpha, original in beta
        assertEquals(11L, p2.reads().online("alpha", "user", null, "u1", T0 + 9_100)
                .features().get("age").value());
        assertEquals(20L, p2.reads().online("beta", "user", null, "u1", T0 + 9_100)
                .features().get("age").value());
        // past as-of remains reproducible (correction invisible)
        assertEquals(10L, p2.reads().offline("alpha", "user", 1,
                List.of(new EntityAtPoint("u1", T0 + 200))).get(0)
                .features().get("age").value());
        // usage rebuilt
        assertEquals(2L, p2.manager().usage("alpha").points());
        assertEquals(1L, p2.manager().usage("beta").points());

        // idempotency restored: replaying job-42 returns the SAME point ids, no append
        p2.time().set(T0 + 99_000);
        BackfillResult replay = p2.backfills().backfill("alpha",
                new BackfillRequest("user", 1, "job-42",
                        List.of(new Correction(w1.pointIds().get(0), "u1", T0 + 100,
                                f("age", 12), "fix again"))));
        assertEquals(1, replay.newPointIds().size());
        StoredPoint corrected = p2.manager().requireState("alpha").offline()
                .getPoint(replay.newPointIds().get(0));
        assertNotNull(corrected);
        assertEquals(11L, corrected.values().get("age")); // previous result, not the replay value
        assertEquals(2L, p2.manager().usage("alpha").points()); // no extra point appended

        // restored limits still reject over-capacity work (beta has 1 point,
        // limit 500): a 500-row batch does not fit and is rejected wholesale
        p2.time().set(T0 + 100_000);
        List<com.github.lechandonga.featurestorejava.model.WriteRow> big = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            big.add(new com.github.lechandonga.featurestorejava.model.WriteRow(
                    T0 + 10_000 + i, f("uid", "bulk" + i, "age", i)));
        }
        com.github.lechandonga.featurestorejava.error.CapacityExceededException cap =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.github.lechandonga.featurestorejava.error.CapacityExceededException.class,
                        () -> p2.writes().submit("beta",
                                new com.github.lechandonga.featurestorejava.model.WriteBatch(
                                        "user", 1, big)));
        assertEquals("POINTS", cap.getDimension());
        assertEquals(500, cap.getLimit());
        assertEquals(1, cap.getCurrentUsage());
        assertEquals(500, cap.getRequested());
        assertEquals(1L, p2.manager().usage("beta").points()); // nothing applied
    }

    @Test
    void legacyV1SnapshotIsImportedIntoDefaultWorkspaceWithoutFalseConflict(@TempDir Path dir)
            throws Exception {
        // build a pre-workspace v1 snapshot EXACTLY like the old release wrote:
        // {"definitions":[...],"points":[...]} with no "workspaces" field
        FixedTimeSource clock = new FixedTimeSource(T0);
        var state = new com.github.lechandonga.featurestorejava.service.StoreState();
        var legacyRegistry = new FeatureRegistry(
                WorkspaceManager.wrappingDefault(state, clock), clock);
        legacyRegistry.register(new RegisterRequest("legacy_user", "uid", null,
                sampleFeatures(), "old"));
        clock.set(T0);
        var legacyWrites = new WriteService(
                WorkspaceManager.wrappingDefault(state, clock), legacyRegistry, clock);
        var written = legacyWrites.submit(
                new com.github.lechandonga.featurestorejava.model.WriteBatch(
                        "legacy_user", 1, List.of(
                                new com.github.lechandonga.featurestorejava.model.WriteRow(
                                        T0 + 100, f("uid", "u1", "age", 3)))));
        List<FeatureSetDef> defs = state.registry().versions("legacy_user");
        StoredPoint point = state.offline().getPoint(written.pointIds().get(0));
        String v1Json = """
                {
                  "definitions" : [ %s ],
                  "points" : [ %s ]
                }
                """.formatted(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(defs.get(0)),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(point));
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(
                dir.resolve("feature-store-snapshot.json"), v1Json);

        // upgraded process starts: data must appear in default workspace
        Process p = Process.start(dir);
        assertEquals(3L, p.reads().online("legacy_user", null, "u1", T0 + 200)
                .features().get("age").value());
        assertEquals(1L, p.manager().usage(WorkspaceManager.DEFAULT_WORKSPACE).points());

        // a NEW workspace registering the SAME name is NOT a conflict: it is a
        // fresh set in its own namespace with independent versioning
        p.manager().create("newteam", null);
        FeatureSetDef created = p.registry().register("newteam",
                new RegisterRequest("legacy_user", "uid", null, sampleFeatures(), "new"));
        assertEquals(1, created.version());
        // old data still readable in default; new workspace set is empty
        assertEquals(3L, p.reads().online("legacy_user", null, "u1", T0 + 200)
                .features().get("age").value());
        assertTrue(p.manager().requireState("newteam").offline()
                .forFeatureSet("legacy_user", 1).isEmpty());
    }
}
