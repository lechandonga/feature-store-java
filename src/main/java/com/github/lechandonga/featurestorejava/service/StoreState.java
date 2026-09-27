package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.store.OfflineStore;
import com.github.lechandonga.featurestorejava.store.OnlineStore;
import com.github.lechandonga.featurestorejava.store.RegistryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Holds the three stores, the per-workspace audit trail, the backfill
 * idempotency table and a single read/write lock that serializes commits
 * against concurrent reads. Readers always observe a consistent snapshot.
 *
 * <p>One {@code StoreState} belongs to exactly one workspace; different
 * workspaces never share stores, locks, audit entries or idempotency state.
 */
public class StoreState {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final RegistryStore registry = new RegistryStore();
    private final OfflineStore offline = new OfflineStore();
    private final OnlineStore online = new OnlineStore();
    private final AuditLog audit;
    private final Map<String, List<String>> correlations = new LinkedHashMap<>();
    private volatile Runnable commitHook;

    public StoreState() {
        this(new AuditLog());
    }

    public StoreState(AuditLog audit) {
        this.audit = audit;
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

    public RegistryStore registry() {
        return registry;
    }

    public OfflineStore offline() {
        return offline;
    }

    public OnlineStore online() {
        return online;
    }

    public AuditLog audit() {
        return audit;
    }

    /** Look up the recorded result of a completed backfill correlation. */
    public List<String> correlationResult(String correlationId) {
        return correlations.get(correlationId);
    }

    public boolean hasCorrelation(String correlationId) {
        return correlations.containsKey(correlationId);
    }

    public void rememberCorrelation(String correlationId, List<String> pointIds) {
        correlations.put(correlationId, List.copyOf(pointIds));
    }

    /** Snapshot of the idempotency table, used when persisting a workspace. */
    public Map<String, List<String>> correlations() {
        return Map.copyOf(correlations);
    }

    public void bootstrap(List<FeatureSetDef> definitions, List<StoredPoint> points) {
        bootstrap(definitions, points, Map.of());
    }

    /**
     * Rebuild in-memory state from a persisted snapshot, also restoring the
     * backfill idempotency table so replayed correlation ids stay idempotent
     * across restarts. Definitions are loaded as-is. Every point is appended to
     * the offline store. Online chains are rebuilt by replay: points are
     * applied in ingest order, and points hidden by a visible correction are
     * dropped so each chain ends with the current value at each event time.
     * Runs once before the app serves traffic.
     */
    public void bootstrap(List<FeatureSetDef> definitions, List<StoredPoint> points,
                          Map<String, List<String>> restoredCorrelations) {
        lock.writeLock().lock();
        try {
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
            if (restoredCorrelations != null) {
                restoredCorrelations.forEach((id, ids) ->
                        correlations.put(id, List.copyOf(ids)));
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
