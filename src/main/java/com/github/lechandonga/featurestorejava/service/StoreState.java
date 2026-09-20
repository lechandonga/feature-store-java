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
 * Holds the three stores and a single read/write lock that serializes commits
 * against concurrent reads. Readers always observe a consistent snapshot.
 */
public class StoreState {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final RegistryStore registry = new RegistryStore();
    private final OfflineStore offline = new OfflineStore();
    private final OnlineStore online = new OnlineStore();
    private volatile Runnable commitHook;

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

    /**
     * Rebuild in-memory state from a persisted snapshot. Definitions are loaded
     * as-is. Every point is appended to the offline store. Online chains are
     * rebuilt by replay: points are applied in ingest order, and points hidden
     * by a visible correction are dropped so each chain ends with the current
     * value at each event time. Runs once before the app serves traffic.
     */
    public void bootstrap(List<FeatureSetDef> definitions, List<StoredPoint> points) {
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
