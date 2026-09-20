package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.FeatureValue;
import com.github.lechandonga.featurestorejava.model.MissingPolicy;
import com.github.lechandonga.featurestorejava.model.OfflineResult;
import com.github.lechandonga.featurestorejava.model.OnlineResult;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.ValueStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Point-in-time-correct reads shared by the online and offline paths.
 *
 * <h2>Bitemporal visibility</h2>
 * A stored point is visible at an as-of time {@code T} only when
 * <em>both</em> timestamps are at or before T:
 * <pre>eventTime &le; T  AND  ingestTime &le; T</pre>
 * This is what prevents label leakage:
 * <ul>
 *   <li>a future-dated event (eventTime &gt; T) is invisible until T catches up;</li>
 *   <li>late data (eventTime &le; T but ingested after T) is invisible at T even
 *       though its business time is old — it simply was not known yet;</li>
 *   <li>a backfill correction appends a new point with a new ingestTime, so at
 *       past T the old value keeps being returned (reproducible), while at
 *       present T the corrected value is returned and the old one is hidden
 *       (its id appears in the new point's {@code supersedes} chain).</li>
 * </ul>
 *
 * <h2>Equivalence</h2>
 * {@link #online} and {@link #offline} resolve every feature through the exact
 * same {@link #resolve} routine on the same offline data. Therefore, for a
 * fixed version, entity and as-of time they cannot diverge; the consistency
 * service verifies this directly.
 */
public class ReadService {

    private final StoreState state;
    private final FeatureRegistry registry;
    private final TimeSource time;

    public ReadService(StoreState state, FeatureRegistry registry, TimeSource time) {
        this.state = state;
        this.registry = registry;
        this.time = time;
    }

    public OnlineResult online(String featureSet, Integer version, String entity, Long asOfTime) {
        long t = asOfTime != null ? asOfTime : time.now();
        FeatureSetDef def = registry.requireVersion(featureSet, version);
        Map<String, FeatureValue> features;
        state.lock().readLock().lock();
        try {
            features = resolve(def, entity, t, true);
        } finally {
            state.lock().readLock().unlock();
        }
        return new OnlineResult(featureSet, def.version(), entity, t, features);
    }

    public List<OfflineResult> offline(String featureSet, Integer version,
                                       List<EntityAtPoint> targets) {
        FeatureSetDef def = registry.requireVersion(featureSet, version);
        List<OfflineResult> results = new ArrayList<>();
        state.lock().readLock().lock();
        try {
            for (EntityAtPoint target : targets) {
                Map<String, FeatureValue> features = resolve(def, target.entity(),
                        target.asOfTime(), false);
                results.add(new OfflineResult(featureSet, def.version(),
                        target.entity(), target.asOfTime(), features));
            }
        } finally {
            state.lock().readLock().unlock();
        }
        return List.copyOf(results);
    }

    // ---- core resolution ------------------------------------------------

    /**
     * Resolve every declared feature for an entity at one as-of time.
     *
     * @param applyTtlOnline TTL expiry is applied only for online reads; an
     *                       offline training point at T uses the value that was
     *                       valid then regardless of the present TTL
     */
    Map<String, FeatureValue> resolve(FeatureSetDef def, String entity, long asOf,
                                      boolean applyTtlOnline) {
        // newest visible point overall; a feature missing from it is treated as
        // missing rather than silently pulled from an older point
        StoredPoint chosen = latestVisiblePoint(def, entity, asOf);

        Map<String, FeatureValue> out = new LinkedHashMap<>();
        for (FeatureDef f : def.features()) {
            FeatureValue fv;
            if (chosen == null || !chosen.values().containsKey(f.name())) {
                fv = missing(f);
            } else {
                Object value = chosen.values().get(f.name());
                boolean expired = applyTtlOnline && def.ttlMillis() != null
                        && asOf - chosen.eventTime() > def.ttlMillis();
                if (expired) {
                    // never silently default: report staleness explicitly
                    fv = new FeatureValue(value, ValueStatus.EXPIRED, chosen.eventTime(),
                            chosen.ingestTime(), chosen.id());
                } else {
                    fv = new FeatureValue(value, ValueStatus.PRESENT, chosen.eventTime(),
                            chosen.ingestTime(), chosen.id());
                }
            }
            out.put(f.name(), fv);
        }
        return Map.copyOf(out);
    }

    /**
     * Newest point visible at {@code asOf} for the entity, or null.
     * Visibility requires {@code eventTime <= asOf && ingestTime <= asOf};
     * a point is hidden if a correction that supersedes it is itself visible.
     */
    StoredPoint latestVisiblePoint(FeatureSetDef def, String entity, long asOf) {
        List<StoredPoint> visible = state.offline().forFeatureSet(def.name(), def.version()).stream()
                .filter(p -> p.entity().equals(entity))
                .filter(p -> p.eventTime() <= asOf && p.ingestTime() <= asOf)
                .sorted((a, b) -> a.eventTime() != b.eventTime()
                        ? Long.compare(a.eventTime(), b.eventTime())
                        : Long.compare(a.ingestTime(), b.ingestTime()))
                .toList();

        Set<String> hidden = new HashSet<>();
        for (StoredPoint p : visible) {
            if (p.supersedes() != null) {
                hidden.add(p.supersedes());
            }
        }
        StoredPoint latest = null;
        for (StoredPoint p : visible) {
            if (!hidden.contains(p.id())) {
                latest = p; // iteration is eventTime then ingestTime ascending
            }
        }
        return latest;
    }

    private FeatureValue missing(FeatureDef f) {
        if (f.missingPolicy() == MissingPolicy.DEFAULT) {
            return new FeatureValue(f.defaultValue(), ValueStatus.DEFAULTED, null, null, null);
        }
        if (f.missingPolicy() == MissingPolicy.REJECT) {
            return new FeatureValue(null, ValueStatus.REJECTED, null, null, null);
        }
        return new FeatureValue(null, ValueStatus.MISSING, null, null, null);
    }
}
