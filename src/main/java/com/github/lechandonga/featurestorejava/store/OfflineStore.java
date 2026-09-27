package com.github.lechandonga.featurestorejava.store;

import com.github.lechandonga.featurestorejava.model.StoredPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only offline (historical) store.
 *
 * <p>Points are never updated or deleted in place. A backfill correction
 * appends a new point whose {@code supersedes} references the corrected one.
 * Appending is the only mutation, and it is additionally serialized by the
 * global commit write-lock; every read returns an independent copy.
 */
public class OfflineStore {

    private final ConcurrentMap<String, CopyOnWriteArrayList<StoredPoint>> byFeatureSet =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StoredPoint> byId = new ConcurrentHashMap<>();

    public void append(StoredPoint point) {
        byId.put(point.id(), point);
        byFeatureSet.computeIfAbsent(key(point.featureSet(), point.version()),
                        k -> new CopyOnWriteArrayList<>())
                .add(point);
    }

    public StoredPoint getPoint(String pointId) {
        return byId.get(pointId);
    }

    public List<StoredPoint> forFeatureSet(String featureSet, int version) {
        CopyOnWriteArrayList<StoredPoint> list = byFeatureSet.get(key(featureSet, version));
        if (list == null) {
            return List.of();
        }
        return List.copyOf(list);
    }

    public List<String> entities(String featureSet, int version) {
        CopyOnWriteArrayList<StoredPoint> list = byFeatureSet.get(key(featureSet, version));
        if (list == null) {
            return List.of();
        }
        return list.stream().map(StoredPoint::entity).distinct().sorted().toList();
    }

    private static String key(String featureSet, int version) {
        return featureSet + "#" + version;
    }

    /** Total number of persisted historical points in this workspace. */
    public int totalPoints() {
        int total = 0;
        for (CopyOnWriteArrayList<StoredPoint> list : byFeatureSet.values()) {
            total += list.size();
        }
        return total;
    }

    /** Snapshot used by optional file persistence. */
    public List<StoredPoint> all() {
        List<StoredPoint> all = new ArrayList<>();
        for (Map.Entry<String, CopyOnWriteArrayList<StoredPoint>> e : byFeatureSet.entrySet()) {
            all.addAll(e.getValue());
        }
        return List.copyOf(all);
    }
}
