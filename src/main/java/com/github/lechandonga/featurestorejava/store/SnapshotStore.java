package com.github.lechandonga.featurestorejava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Very small JSON file snapshot for running the store locally across restarts.
 *
 * <p>Writes are atomic: data goes to a temp file in the same directory and is
 * then moved over the target, so a crash mid-save never leaves a torn JSON.
 * Loading tolerates absent files (first run) and rebuilds the online chains
 * from the append-only points (the latest surviving point per event time).
 *
 * <p>Two on-disk shapes are supported:
 * <ul>
 *   <li><b>v2 (workspaces)</b>: {@code {"version":2,"workspaces":[...]}} with
 *       each workspace carrying its metadata (incl. capacity limits), all
 *       definitions/points, and the backfill idempotency table;</li>
 *   <li><b>v1 (legacy)</b>: {@code {"definitions":[...],"points":[...]}} —
 *       everything is attributed to the {@code default} workspace on load, so
 *       pre-upgrade data keeps being readable and never collides with a
 *       same-named feature set created in another workspace.</li>
 * </ul>
 */
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public SnapshotStore(Path dir) {
        this.dir = dir;
    }

    /** Legacy single-workspace snapshot (v1). */
    public record Snapshot(List<FeatureSetDef> definitions, List<StoredPoint> points) {
    }

    /** Workspace-aware snapshot envelope (v2). */
    public record WorkspaceEnvelope(int version, List<WorkspaceSnapshot> workspaces) {
    }

    /**
     * Load either format. Returns a v2 envelope; legacy files are converted
     * transparently into a single {@code default} workspace snapshot.
     */
    public WorkspaceEnvelope loadEnvelope() {
        Path f = dir.resolve("feature-store-snapshot.json");
        if (!Files.exists(f)) {
            return new WorkspaceEnvelope(2, List.of());
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(f.toFile());
            if (root.has("workspaces")) {
                WorkspaceEnvelope env = mapper.convertValue(root,
                        new TypeReference<WorkspaceEnvelope>() {
                        });
                int totalDefs = env.workspaces().stream()
                        .mapToInt(w -> w.definitions() == null ? 0 : w.definitions().size()).sum();
                int totalPoints = env.workspaces().stream()
                        .mapToInt(w -> w.points() == null ? 0 : w.points().size()).sum();
                log.info("loaded workspace snapshot v2: {} workspace(s), {} definition(s), {} point(s)",
                        env.workspaces().size(), totalDefs, totalPoints);
                return env;
            }
            // legacy v1: attribute all data to the default workspace
            Snapshot legacy = mapper.convertValue(root, new TypeReference<Snapshot>() {
            });
            log.info("loaded legacy snapshot v1: {} definition(s), {} point(s) -> workspace 'default'",
                    legacy.definitions().size(), legacy.points().size());
            WorkspaceSnapshot def = new WorkspaceSnapshot(
                    com.github.lechandonga.featurestorejava.workspace.WorkspaceManager.DEFAULT_WORKSPACE,
                    0L, null, legacy.definitions(), legacy.points(), null);
            return new WorkspaceEnvelope(2, List.of(def));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read snapshot at " + f, e);
        }
    }

    /** Legacy loader retained for tests and tooling. */
    public Snapshot load() {
        WorkspaceEnvelope env = loadEnvelope();
        if (env.workspaces().isEmpty()) {
            return new Snapshot(List.of(), List.of());
        }
        // merge view is only used by legacy callers; default workspace first
        var first = env.workspaces().get(0);
        return new Snapshot(
                first.definitions() == null ? List.of() : first.definitions(),
                first.points() == null ? List.of() : first.points());
    }

    public void saveEnvelope(List<WorkspaceSnapshot> workspaces) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve("feature-store-snapshot.json");
            Path tmp = dir.resolve("feature-store-snapshot.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),
                    new WorkspaceEnvelope(2, workspaces));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist snapshot under " + dir, e);
        }
    }

    public void save(List<FeatureSetDef> definitions, List<StoredPoint> points) {
        WorkspaceSnapshot def = new WorkspaceSnapshot(
                com.github.lechandonga.featurestorejava.workspace.WorkspaceManager.DEFAULT_WORKSPACE,
                0L, null, definitions, points, null);
        saveEnvelope(List.of(def));
    }
}
