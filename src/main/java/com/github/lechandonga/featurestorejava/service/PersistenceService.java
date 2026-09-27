package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.WorkspaceInfo;
import com.github.lechandonga.featurestorejava.model.WorkspaceLimits;
import com.github.lechandonga.featurestorejava.store.SnapshotStore;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceManager;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceSnapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the local JSON snapshot on startup and persists after each commit,
 * preserving full workspace isolation.
 *
 * <p>On startup every workspace in the v2 snapshot (or the single
 * {@code default} workspace synthesized from a legacy v1 file) is rebuilt:
 * metadata and capacity limits are re-registered, definitions and points are
 * bootstrapped with type normalization and online-chain replay, and the
 * backfill idempotency table is restored so correlation ids stay idempotent.
 *
 * <p>Each workspace's commit hook triggers one full, atomic rewrite of the
 * envelope. Persistence is intentionally simple and durability-bounded: it is
 * meant for local runs and tests, not high-volume production traffic (saving
 * happens outside the workspace read lock).
 */
@Service
public class PersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final WorkspaceManager workspaces;
    private final SnapshotStore snapshots;

    public PersistenceService(
            WorkspaceManager workspaces,
            @Value("${feature-store.data-dir:./data}") String dataDir) {
        this.workspaces = workspaces;
        this.snapshots = new SnapshotStore(Path.of(dataDir));
    }

    @PostConstruct
    public void startup() {
        SnapshotStore.WorkspaceEnvelope env = snapshots.loadEnvelope();
        if (env.workspaces().isEmpty()) {
            log.info("no existing snapshot; starting with the empty default workspace");
        } else {
            for (WorkspaceSnapshot ws : env.workspaces()) {
                restoreWorkspace(ws);
            }
        }
        // persist outside the commit lock after each successful write/backfill,
        // independently wired for every workspace (existing and future)
        workspaces.setCreateHook(created -> {
            created.state().setCommitHook(this::persist);
            persist();
        });
        for (WorkspaceManager.Instance ws : workspaces.all()) {
            ws.state().setCommitHook(this::persist);
        }
    }

    private void restoreWorkspace(WorkspaceSnapshot snap) {
        WorkspaceLimits limits = snap.limits() == null
                ? new WorkspaceLimits(null, null) : snap.limits();
        long createdAt = snap.createdAt() == null ? 0L : snap.createdAt();
        WorkspaceInfo info = new WorkspaceInfo(snap.name(), createdAt, limits);
        StoreState state;
        if (WorkspaceManager.DEFAULT_WORKSPACE.equals(snap.name())) {
            // reuse the pre-created default state (created at bean startup)
            state = workspaces.require(WorkspaceManager.DEFAULT_WORKSPACE).state();
            workspaces.restore(info, state, snap.correlations());
        } else {
            state = new StoreState();
            workspaces.restore(info, state, snap.correlations());
        }
        List<FeatureSetDef> definitions = snap.definitions() == null
                ? List.of() : snap.definitions();
        List<StoredPoint> points = snap.points() == null ? List.of() : snap.points();
        if (!definitions.isEmpty() || !points.isEmpty()) {
            state.bootstrap(definitions, points);
        }
        log.info("restored workspace '{}': {} definition(s), {} point(s), {} correlation(s)",
                snap.name(), definitions.size(), points.size(),
                snap.correlations() == null ? 0 : snap.correlations().size());
    }

    /** Persist every workspace (metadata, limits, definitions, points, idempotency). */
    public void persist() {
        List<WorkspaceSnapshot> out = new ArrayList<>();
        for (WorkspaceManager.Instance ws : workspaces.all()) {
            StoreState state = ws.state();
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
                out.add(new WorkspaceSnapshot(ws.name(), ws.info().createdAt(),
                        ws.info().limits(), List.copyOf(definitions), List.copyOf(points),
                        state.correlations()));
            } finally {
                state.lock().readLock().unlock();
            }
        }
        snapshots.saveEnvelope(out);
    }
}
