package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.store.SnapshotStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the local JSON snapshot on startup and persists after each commit.
 *
 * <p>Persistence is intentionally simple and durability-bounded: the full
 * dataset is rewritten atomically. It is meant for local runs and tests, not
 * high-volume production traffic (the in-memory stores themselves are the hot
 * path; saving happens outside their read lock).
 */
@Service
public class PersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final StoreState state;
    private final SnapshotStore snapshots;

    public PersistenceService(
            StoreState state,
            @Value("${feature-store.data-dir:./data}") String dataDir) {
        this.state = state;
        this.snapshots = new SnapshotStore(Path.of(dataDir));
    }

    @PostConstruct
    void load() {
        SnapshotStore.Snapshot snap = snapshots.load();
        if (!snap.definitions().isEmpty() || !snap.points().isEmpty()) {
            state.bootstrap(snap.definitions(), snap.points());
        } else {
            log.info("no existing snapshot; starting with an empty feature store");
        }
        // persist outside the commit lock after each successful write/backfill
        state.setCommitHook(this::persist);
    }

    /** Persist every registered version and all offline points. */
    public void persist() {
        List<FeatureSetDef> definitions = new ArrayList<>();
        List<StoredPoint> points = new ArrayList<>();
        state.lock().readLock().lock();
        try {
            for (FeatureSetDef latest : state.registry().allLatest()) {
                definitions.addAll(state.registry().versions(latest.name()));
                for (FeatureSetDef v : state.registry().versions(latest.name())) {
                    points.addAll(state.offline().forFeatureSet(v.name(), v.version()));
                }
            }
        } finally {
            state.lock().readLock().unlock();
        }
        snapshots.save(definitions, points);
    }
}
