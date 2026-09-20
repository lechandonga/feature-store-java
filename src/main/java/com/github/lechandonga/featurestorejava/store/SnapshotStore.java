package com.github.lechandonga.featurestorejava.store;

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

/**
 * Very small JSON file snapshot for running the store locally across restarts.
 *
 * <p>Writes are atomic: data goes to a temp file in the same directory and is
 * then moved over the target, so a crash mid-save never leaves a torn JSON.
 * Loading tolerates absent files (first run) and rebuilds the online chains
 * from the append-only points (the latest surviving point per event time).
 */
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public SnapshotStore(Path dir) {
        this.dir = dir;
    }

    public record Snapshot(List<FeatureSetDef> definitions, List<StoredPoint> points) {
    }

    public Snapshot load() {
        Path f = dir.resolve("feature-store-snapshot.json");
        if (!Files.exists(f)) {
            return new Snapshot(List.of(), List.of());
        }
        try {
            Snapshot snap = mapper.readValue(f.toFile(), new TypeReference<>() {
            });
            log.info("loaded feature store snapshot: {} definition(s), {} point(s)",
                    snap.definitions().size(), snap.points().size());
            return snap;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read snapshot at " + f, e);
        }
    }

    public void save(List<FeatureSetDef> definitions, List<StoredPoint> points) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve("feature-store-snapshot.json");
            Path tmp = dir.resolve("feature-store-snapshot.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),
                    new Snapshot(definitions, points));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist snapshot under " + dir, e);
        }
    }
}
