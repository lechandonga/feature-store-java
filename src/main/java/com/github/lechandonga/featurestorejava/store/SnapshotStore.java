package com.github.lechandonga.featurestorejava.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Very small JSON file snapshot for running the store locally across restarts.
 *
 * <p>Writes are atomic: data goes to a temp file in the same directory and is
 * then moved over the target, so a crash mid-save never leaves a torn JSON.
 * Loading tolerates absent files (first run) and rebuilds the online chains
 * from the append-only points (the latest surviving point per event time).
 *
 * <p>The current format persists one {@link WorkspaceData} entry per
 * workspace (definitions, points, capacity limits and backfill idempotency
 * records). Snapshots written before workspaces existed — a top-level
 * {@code {definitions, points}} object — are still loaded and land in the
 * {@code default} workspace unchanged.
 */
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    public static final String DEFAULT_WORKSPACE = "default";

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public SnapshotStore(Path dir) {
        this.dir = dir;
    }

    /** Persisted state of one workspace. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceData(
            String name,
            Long maxFeatureSets,
            Long maxPoints,
            List<FeatureSetDef> definitions,
            List<StoredPoint> points,
            Map<String, List<String>> correlations
    ) {
    }

    /**
     * Whole-store snapshot. {@code workspaces} is the current format;
     * {@code definitions}/{@code points} are the legacy single-tenant format,
     * normalized into a default-workspace entry on load.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshot(List<WorkspaceData> workspaces,
                           List<FeatureSetDef> definitions,
                           List<StoredPoint> points) {

        public Snapshot {
            if ((workspaces == null || workspaces.isEmpty())
                    && ((definitions != null && !definitions.isEmpty())
                        || (points != null && !points.isEmpty()))) {
                // legacy format: everything belongs to the default workspace
                workspaces = List.of(new WorkspaceData(DEFAULT_WORKSPACE, null, null,
                        definitions == null ? List.of() : definitions,
                        points == null ? List.of() : points,
                        Map.of()));
            }
            workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
        }

        /** Definitions of the default workspace (legacy accessor). */
        @Override
        public List<FeatureSetDef> definitions() {
            return definitions != null ? definitions : defaultDefinitions();
        }

        /** Points of the default workspace (legacy accessor). */
        @Override
        public List<StoredPoint> points() {
            return points != null ? points : defaultPoints();
        }

        private List<FeatureSetDef> defaultDefinitions() {            return workspaces.stream()
                    .filter(w -> DEFAULT_WORKSPACE.equals(w.name()))
                    .findFirst()
                    .map(w -> w.definitions() == null ? List.<FeatureSetDef>of() : w.definitions())
                    .orElse(List.of());
        }

        /** Points of the default workspace (legacy accessor). */
        private List<StoredPoint> defaultPoints() {
            return workspaces.stream()
                    .filter(w -> DEFAULT_WORKSPACE.equals(w.name()))
                    .findFirst()
                    .map(w -> w.points() == null ? List.<StoredPoint>of() : w.points())
                    .orElse(List.of());
        }
    }

    public Snapshot load() {
        Path f = dir.resolve("feature-store-snapshot.json");
        if (!Files.exists(f)) {
            return new Snapshot(List.of(), List.of(), List.of());
        }
        try {
            Snapshot snap = mapper.readValue(f.toFile(), new TypeReference<>() {
            });
            log.info("loaded feature store snapshot: {} workspace(s)",
                    snap.workspaces().size());
            return snap;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read snapshot at " + f, e);
        }
    }

    /** Persist every workspace. */
    public void save(List<WorkspaceData> workspaces) {
        save(new Snapshot(workspaces, null, null));
    }

    /** Persist a single default workspace (legacy call shape). */
    public void save(List<FeatureSetDef> definitions, List<StoredPoint> points) {
        save(List.of(new WorkspaceData(DEFAULT_WORKSPACE, null, null,
                definitions, points, Map.of())));
    }

    private void save(Snapshot snapshot) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve("feature-store-snapshot.json");
            Path tmp = dir.resolve("feature-store-snapshot.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snapshot);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist snapshot under " + dir, e);
        }
    }
}
