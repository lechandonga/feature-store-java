package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.WorkspaceDef;
import com.github.lechandonga.featurestorejava.store.SnapshotStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the local JSON snapshot on startup and persists after each commit.
 *
 * <p>Persistence is intentionally simple and durability-bounded: the full
 * dataset of every workspace (definitions, points, capacity limits and
 * backfill idempotency records) is rewritten atomically. It is meant for
 * local runs and tests, not high-volume production traffic (the in-memory
 * stores themselves are the hot path; saving happens outside their read
 * lock). Snapshots written before workspaces existed load into the default
 * workspace unchanged.
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
    public void load() {
        SnapshotStore.Snapshot snap = snapshots.load();
        if (snap.workspaces().isEmpty()) {
            log.info("no existing snapshot; starting with an empty feature store");
        } else {
            for (SnapshotStore.WorkspaceData w : snap.workspaces()) {
                state.ensureWorkspace(new WorkspaceDef(w.name(), w.maxFeatureSets(),
                        w.maxPoints(), 0L, null));
                state.bootstrap(w.name(),
                        w.definitions() == null ? List.of() : w.definitions(),
                        w.points() == null ? List.of() : w.points());
                state.loadCorrelations(w.name(), w.correlations());
                log.info("restored workspace '{}': {} definition(s), {} point(s),"
                                + " {} idempotency record(s)",
                        w.name(),
                        w.definitions() == null ? 0 : w.definitions().size(),
                        w.points() == null ? 0 : w.points().size(),
                        w.correlations() == null ? 0 : w.correlations().size());
            }
        }
        // persist outside the commit lock after each successful write/backfill
        state.setCommitHook(this::persist);
    }

    /** Persist every workspace: versions, points, limits and idempotency. */
    public void persist() {
        List<SnapshotStore.WorkspaceData> workspaces = new ArrayList<>();
        state.lock().readLock().lock();
        try {
            for (String name : state.workspaceNames()) {
                WorkspaceDef def = state.workspaceDef(name);
                List<FeatureSetDef> definitions = new ArrayList<>();
                List<StoredPoint> points = new ArrayList<>();
                for (FeatureSetDef latest : state.registry(name).allLatest()) {
                    definitions.addAll(state.registry(name).versions(latest.name()));
                    for (FeatureSetDef v : state.registry(name).versions(latest.name())) {
                        points.addAll(state.offline(name).forFeatureSet(v.name(), v.version()));
                    }
                }
                Map<String, List<String>> correlations;
                synchronized (state.correlations(name)) {
                    correlations = new LinkedHashMap<>(state.correlations(name));
                }
                workspaces.add(new SnapshotStore.WorkspaceData(name, def.maxFeatureSets(),
                        def.maxPoints(), definitions, points, correlations));
            }
        } finally {
            state.lock().readLock().unlock();
        }
        snapshots.save(workspaces);
    }
}
