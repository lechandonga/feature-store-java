package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.error.WorkspaceCapacityExceededException;
import com.github.lechandonga.featurestorejava.error.WorkspaceNotFoundException;
import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.WorkspaceDef;
import com.github.lechandonga.featurestorejava.model.WorkspaceUsage;
import com.github.lechandonga.featurestorejava.store.OfflineStore;
import com.github.lechandonga.featurestorejava.store.OnlineStore;
import com.github.lechandonga.featurestorejava.store.RegistryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Holds one isolated store triple (registry / offline / online) per workspace
 * plus a single read/write lock that serializes commits against concurrent
 * reads across ALL workspaces. Readers always observe a consistent snapshot of
 * the workspace they address and can never see another workspace's data or a
 * half-applied commit.
 *
 * <p>The {@value #DEFAULT_WORKSPACE} workspace always exists: every operation
 * that does not name a workspace — including all pre-workspace-era calls and
 * snapshots — is served from it.
 */
public class StoreState {

    public static final String DEFAULT_WORKSPACE = "default";

    /** Isolated per-workspace state: stores, limits and backfill idempotency. */
    private static final class WorkspaceState {
        final RegistryStore registry = new RegistryStore();
        final OfflineStore offline = new OfflineStore();
        final OnlineStore online = new OnlineStore();
        // correlationId -> applied point ids; mutated only under the write lock
        final Map<String, List<String>> correlations = new LinkedHashMap<>();
        volatile WorkspaceDef def;

        WorkspaceState(WorkspaceDef def) {
            this.def = def;
        }
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ConcurrentMap<String, WorkspaceState> workspaces = new ConcurrentHashMap<>();
    private volatile Runnable commitHook;

    public StoreState() {
        workspaces.put(DEFAULT_WORKSPACE, new WorkspaceState(
                new WorkspaceDef(DEFAULT_WORKSPACE, null, null, 0L,
                        "default workspace (pre-existing data)")));
    }

    /**
     * Register a callback invoked (outside the commit write-lock) after every
     * successful write/backfill commit. Used for snapshot persistence.
     */
    public void setCommitHook(Runnable commitHook) {
        this.commitHook = commitHook;
    }

    public void notifyCommit() {
        Runnable hook = commitHook;
        if (hook != null) {
            hook.run();
        }
    }

    public ReentrantReadWriteLock lock() {
        return lock;
    }

    // ---- workspace management --------------------------------------------

    /** Normalize a caller-supplied workspace name; null/blank means default. */
    public static String normalizeWorkspace(String workspace) {
        return workspace == null || workspace.isBlank() ? DEFAULT_WORKSPACE : workspace;
    }

    public boolean workspaceExists(String name) {
        return workspaces.containsKey(name);
    }

    /** Create a workspace; returns false if the name is already taken. */
    public boolean createWorkspace(WorkspaceDef def) {
        lock.writeLock().lock();
        try {
            return workspaces.putIfAbsent(def.name(), new WorkspaceState(def)) == null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Create the workspace if absent; on restart also restores its limits. */
    public void ensureWorkspace(WorkspaceDef def) {
        lock.writeLock().lock();
        try {
            WorkspaceState existing = workspaces.get(def.name());
            if (existing == null) {
                workspaces.put(def.name(), new WorkspaceState(def));
            } else {
                existing.def = def;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public WorkspaceDef workspaceDef(String name) {
        return require(name).def;
    }

    public List<String> workspaceNames() {
        return workspaces.keySet().stream().sorted().toList();
    }

    public WorkspaceUsage usage(String name) {
        WorkspaceState w = require(name);
        lock.readLock().lock();
        try {
            return new WorkspaceUsage(name, w.registry.allLatest().size(),
                    w.def.maxFeatureSets(), w.offline.totalCount(), w.def.maxPoints(),
                    w.def.createdAt());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---- capacity enforcement (called with the write lock already held) ---

    /** Reject a registration that would exceed the workspace feature-set cap. */
    public void ensureFeatureSetCapacity(String workspace, String featureSet) {
        WorkspaceState w = require(workspace);
        Long max = w.def.maxFeatureSets();
        if (max == null) {
            return;
        }
        long current = w.registry.allLatest().size();
        // re-registering an existing name does not consume new capacity
        boolean isNew = w.registry.getLatest(featureSet) == null;
        if (isNew && current >= max) {
            throw new WorkspaceCapacityExceededException(workspace, featureSet,
                    "featureSets", max, current, 1);
        }
    }

    /** Reject a write/backfill batch that would exceed the workspace point cap. */
    public void ensurePointCapacity(String workspace, String featureSet, int additional) {
        WorkspaceState w = require(workspace);
        Long max = w.def.maxPoints();
        if (max == null) {
            return;
        }
        long current = w.offline.totalCount();
        if (current + additional > max) {
            throw new WorkspaceCapacityExceededException(workspace, featureSet,
                    "points", max, current, additional);
        }
    }

    // ---- per-workspace store access ---------------------------------------

    private WorkspaceState require(String workspace) {
        WorkspaceState w = workspaces.get(normalizeWorkspace(workspace));
        if (w == null) {
            throw new WorkspaceNotFoundException(workspace);
        }
        return w;
    }

    public RegistryStore registry(String workspace) {
        return require(workspace).registry;
    }

    public OfflineStore offline(String workspace) {
        return require(workspace).offline;
    }

    public OnlineStore online(String workspace) {
        return require(workspace).online;
    }

    /** Idempotency records of one workspace (access under the write lock). */
    public Map<String, List<String>> correlations(String workspace) {
        return require(workspace).correlations;
    }

    /** Restore idempotency records during snapshot replay. */
    public void loadCorrelations(String workspace, Map<String, List<String>> correlations) {
        if (correlations != null) {
            require(workspace).correlations.putAll(correlations);
        }
    }

    /**
     * Name of the workspace owning a stored point id, or null. Used to turn a
     * cross-workspace point reference into an explainable rejection instead of
     * a plain "not found".
     */
    public String workspaceOfPoint(String pointId) {
        for (Map.Entry<String, WorkspaceState> e : workspaces.entrySet()) {
            if (e.getValue().offline.getPoint(pointId) != null) {
                return e.getKey();
            }
        }
        return null;
    }

    // ---- default-workspace accessors (pre-workspace call sites) -----------

    public RegistryStore registry() {
        return registry(DEFAULT_WORKSPACE);
    }

    public OfflineStore offline() {
        return offline(DEFAULT_WORKSPACE);
    }

    public OnlineStore online() {
        return online(DEFAULT_WORKSPACE);
    }

    // ---- snapshot replay ----------------------------------------------------

    /** Bootstrap the default workspace (legacy single-tenant snapshot). */
    public void bootstrap(List<FeatureSetDef> definitions, List<StoredPoint> points) {
        bootstrap(DEFAULT_WORKSPACE, definitions, points);
    }

    /**
     * Rebuild one workspace's in-memory state from a persisted snapshot.
     * Definitions are loaded as-is. Every point is appended to the offline
     * store. Online chains are rebuilt by replay: points are applied in ingest
     * order, and points hidden by a visible correction are dropped so each
     * chain ends with the current value at each event time. Runs once before
     * the app serves traffic.
     */
    public void bootstrap(String workspace, List<FeatureSetDef> definitions,
                          List<StoredPoint> points) {
        lock.writeLock().lock();
        try {
            RegistryStore registry = registry(workspace);
            OfflineStore offline = offline(workspace);
            OnlineStore online = online(workspace);
            definitions.forEach(registry::put);

            // definitions drive type normalization: a JSON round trip turns a
            // small integral value into Integer, so re-apply each feature's
            // canonical Java type (LONG -> Long, DOUBLE -> Double) on reload
            Map<String, FeatureSetDef> defByKey = new LinkedHashMap<>();
            for (FeatureSetDef d : definitions) {
                defByKey.put(d.name() + "#" + d.version(), d);
            }

            Map<String, List<StoredPoint>> bySetVersion = new LinkedHashMap<>();
            for (StoredPoint p : points) {
                StoredPoint normalized = normalize(p,
                        defByKey.get(p.featureSet() + "#" + p.version()));
                offline.append(normalized);
                bySetVersion.computeIfAbsent(p.featureSet() + "#" + p.version(),
                        k -> new ArrayList<>()).add(normalized);
            }
            for (Map.Entry<String, List<StoredPoint>> e : bySetVersion.entrySet()) {
                String[] parts = e.getKey().split("#", 2);
                String featureSet = parts[0];
                int version = Integer.parseInt(parts[1]);
                List<StoredPoint> ordered = e.getValue().stream()
                        .sorted(Comparator.comparingLong(StoredPoint::ingestTime))
                        .toList();
                online.applyBatch(featureSet, version, ordered);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private StoredPoint normalize(StoredPoint p, FeatureSetDef def) {
        if (def == null) {
            return p;
        }
        Map<String, FeatureDef> types = new LinkedHashMap<>();
        for (FeatureDef f : def.features()) {
            types.put(f.name(), f);
        }
        Map<String, Object> values = new TreeMap<>();
        for (Map.Entry<String, Object> e : p.values().entrySet()) {
            FeatureDef f = types.get(e.getKey());
            Object v = e.getValue();
            values.put(e.getKey(), f != null && v != null ? f.type().normalize(v) : v);
        }
        return new StoredPoint(p.id(), p.featureSet(), p.version(), p.entity(),
                p.eventTime(), p.ingestTime(), values, p.supersedes(), p.source(),
                p.reason());
    }
}
