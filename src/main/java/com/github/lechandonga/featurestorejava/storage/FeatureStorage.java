package com.github.lechandonga.featurestorejava.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import com.github.lechandonga.featurestorejava.domain.FeatureRecord;
import com.github.lechandonga.featurestorejava.domain.RecordOrigin;
import org.springframework.stereotype.Component;

/**
 * In-memory concurrent feature record storage.
 *
 * <p>A validated batch is converted into one immutable snapshot object and published by a single
 * reference swap while holding the write lock. Readers always observe a fully consistent snapshot
 * (copy-on-write semantics): a concurrent backfill can never expose half-applied rows.
 *
 * <p>Key layout: (featureName, version, entityId) -> TreeMap<eventTs, List<revision>>.
 * Multiple revisions of the same (entity, eventTs) are kept as an immutable append list so
 * corrections remain explainable.
 */
@Component
public class FeatureStorage {

    private record Key(String featureName, int version, String entityId) {
    }

    private static final class Snapshot {
        final Map<Key, TreeMap<Long, List<FeatureRecord>>> data;

        Snapshot(Map<Key, TreeMap<Long, List<FeatureRecord>>> data) {
            this.data = data;
        }
    }

    private volatile Snapshot snapshot = new Snapshot(new ConcurrentHashMap<>());
    private final ReentrantReadWriteLock commitLock = new ReentrantReadWriteLock();

    /** Capture an immutable snapshot handle; all reads through it see one consistent commit point. */
    public Object captureSnapshot() {
        return snapshot;
    }

    /** Snapshot-bound scan: a multi-feature read taken against one handle cannot straddle commits. */
    @SuppressWarnings("unchecked")
    public List<FeatureRecord> scan(Object snapshotHandle, String featureName, int version, String entityId) {
        Snapshot s = (Snapshot) snapshotHandle;
        TreeMap<Long, List<FeatureRecord>> events =
                s.data.get(new Key(featureName, version, entityId));
        if (events == null) {
            return List.of();
        }
        List<FeatureRecord> out = new ArrayList<>();
        events.values().forEach(out::addAll);
        return List.copyOf(out);
    }

    /** Atomically apply a fully validated batch (all rows visible together or none). */
    public void commitBatch(List<FeatureRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        commitLock.writeLock().lock();
        try {
            Map<Key, TreeMap<Long, List<FeatureRecord>>> current = snapshot.data;
            Map<Key, TreeMap<Long, List<FeatureRecord>>> copy = new ConcurrentHashMap<>(current);
            for (FeatureRecord rec : records) {
                Key key = new Key(rec.featureName(), rec.version(), rec.entityId());
                TreeMap<Long, List<FeatureRecord>> events = new TreeMap<>(copy.getOrDefault(key, new TreeMap<>()));
                List<FeatureRecord> revisions = new ArrayList<>(events.getOrDefault(rec.eventTsMs(), List.of()));
                revisions.add(rec);
                events.put(rec.eventTsMs(), List.copyOf(revisions));
                copy.put(key, events);
            }
            snapshot = new Snapshot(copy);
        } finally {
            commitLock.writeLock().unlock();
        }
    }

    /** All revision records (immutable snapshot view) for one feature/entity, ordered by event time. */
    public List<FeatureRecord> scan(String featureName, int version, String entityId) {
        Snapshot s = snapshot;
        TreeMap<Long, List<FeatureRecord>> events =
                s.data.get(new Key(featureName, version, entityId));
        if (events == null) {
            return List.of();
        }
        List<FeatureRecord> out = new ArrayList<>();
        events.values().forEach(out::addAll);
        return List.copyOf(out);
    }

    /** Revisions for the exact (feature, version, entity, eventTs) key, in commit order. */
    public List<FeatureRecord> revisions(String featureName, int version, String entityId, long eventTsMs) {
        TreeMap<Long, List<FeatureRecord>> events =
                snapshot.data.get(new Key(featureName, version, entityId));
        return events == null ? List.of() : events.getOrDefault(eventTsMs, List.of());
    }

    /** High-water mark of event time among live INGEST records; null if none. */
    public Long latestIngestedEventTs(String featureName, int version, String entityId) {
        TreeMap<Long, List<FeatureRecord>> events =
                snapshot.data.get(new Key(featureName, version, entityId));
        if (events == null) {
            return null;
        }
        long max = Long.MIN_VALUE;
        for (Map.Entry<Long, List<FeatureRecord>> e : events.entrySet()) {
            for (FeatureRecord r : e.getValue()) {
                if (r.origin() == RecordOrigin.INGEST && e.getKey() > max) {
                    max = e.getKey();
                }
            }
        }
        return max == Long.MIN_VALUE ? null : max;
    }
}
