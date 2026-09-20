package com.github.lechandonga.featurestorejava.store;

import com.github.lechandonga.featurestorejava.model.StoredPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Online store with per-entity immutable point chains.
 *
 * <p>For each (featureSet, version, entity) the store holds a
 * {@link NavigableMap} from event time to the <em>currently visible</em>
 * {@link StoredPoint}. A batch rebuilds every entity chain it touches and
 * installs the new maps in one synchronized critical section: a reader either
 * sees the whole recomputed chain or the old one, never a half-applied update.
 *
 * <p>A backfill correction always targets the original event time (business
 * time is never rewritten), so appending the corrected point with the same
 * event time atomically overrides the stale value in the chain. The offline
 * store retains both points, so the correction stays auditable.
 */
public class OnlineStore {

    // featureSet#version -> entity -> eventTime -> currently visible point
    private final ConcurrentMap<String, ConcurrentMap<String, NavigableMap<Long, StoredPoint>>> data =
            new ConcurrentHashMap<>();

    /**
     * Rebuild and atomically install the chains of every entity touched by one
     * commit (a write batch or a backfill batch).
     */
    public void applyBatch(String featureSet, int version, List<StoredPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        ConcurrentMap<String, NavigableMap<Long, StoredPoint>> byEntity =
                data.computeIfAbsent(key(featureSet, version), k -> new ConcurrentHashMap<>());

        Map<String, List<StoredPoint>> incomingByEntity = new java.util.HashMap<>();
        for (StoredPoint p : points) {
            incomingByEntity.computeIfAbsent(p.entity(), k -> new ArrayList<>()).add(p);
        }

        synchronized (byEntity) {
            for (Map.Entry<String, List<StoredPoint>> e : incomingByEntity.entrySet()) {
                TreeMap<Long, StoredPoint> rebuilt = new TreeMap<>();
                NavigableMap<Long, StoredPoint> old = byEntity.get(e.getKey());
                if (old != null) {
                    rebuilt.putAll(old);
                }
                // append order matters: the corrected point arrives last and
                // overrides the stale point at the same event time
                for (StoredPoint p : e.getValue()) {
                    rebuilt.put(p.eventTime(), p);
                }
                byEntity.put(e.getKey(), java.util.Collections.unmodifiableNavigableMap(rebuilt));
            }
        }
    }

    /**
     * Immutable chain for an entity, ordered by event time ascending.
     */
    public List<StoredPoint> chain(String featureSet, int version, String entity) {
        String setKey = key(featureSet, version);
        ConcurrentMap<String, NavigableMap<Long, StoredPoint>> byEntity = data.get(setKey);
        if (byEntity == null) {
            return List.of();
        }
        NavigableMap<Long, StoredPoint> chain = byEntity.get(entity);
        return chain == null ? List.of() : List.copyOf(chain.values());
    }

    public List<String> entities(String featureSet, int version) {
        ConcurrentMap<String, NavigableMap<Long, StoredPoint>> byEntity =
                data.get(key(featureSet, version));
        return byEntity == null ? List.of() : byEntity.keySet().stream().sorted().toList();
    }

    private static String key(String featureSet, int version) {
        return featureSet + "#" + version;
    }
}
