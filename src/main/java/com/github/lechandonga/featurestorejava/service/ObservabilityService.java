package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.CoverageReport;
import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.FeatureValue;
import com.github.lechandonga.featurestorejava.model.FreshnessReport;
import com.github.lechandonga.featurestorejava.model.StaleEntity;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.ValueStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Freshness and coverage observation.
 *
 * <p>Nothing is hidden behind a default value: an entity whose newest visible
 * point is older than the feature set TTL is listed as stale (and online reads
 * mark its features {@code EXPIRED}); coverage counts only genuinely
 * {@code PRESENT} (entity, feature) cells.
 */
public class ObservabilityService {

    private final StoreState state;
    private final FeatureRegistry registry;
    private final ReadService reads;
    private final TimeSource time;

    public ObservabilityService(StoreState state, FeatureRegistry registry,
                                ReadService reads, TimeSource time) {
        this.state = state;
        this.registry = registry;
        this.reads = reads;
        this.time = time;
    }

    public FreshnessReport freshness(String featureSet, Integer version, Long observedAt) {
        long now = observedAt != null ? observedAt : time.now();
        FeatureSetDef def = registry.requireVersion(featureSet, version);

        List<StaleEntity> stale = new ArrayList<>();
        state.lock().readLock().lock();
        try {
            List<String> entities = state.offline().entities(def.name(), def.version());
            for (String entity : entities) {
                StoredPoint latest = reads.latestVisiblePoint(def, entity, now);
                if (latest == null) {
                    continue; // nothing visible yet — covered by coverage report
                }
                long age = now - latest.eventTime();
                if (def.ttlMillis() != null && age > def.ttlMillis()) {
                    stale.add(new StaleEntity(entity, latest.eventTime(), age));
                }
            }
        } finally {
            state.lock().readLock().unlock();
        }
        // no TTL configured: nothing can be stale by definition, but the report
        // still states the policy explicitly
        boolean fresh = def.ttlMillis() == null || stale.isEmpty();
        return new FreshnessReport(def.name(), def.version(), now, def.ttlMillis(),
                fresh, List.copyOf(stale));
    }

    public CoverageReport coverage(String featureSet, Integer version, Long observedAt) {
        long now = observedAt != null ? observedAt : time.now();
        FeatureSetDef def = registry.requireVersion(featureSet, version);

        int present;
        int entities;
        state.lock().readLock().lock();
        try {
            List<String> entityIds = state.offline().entities(def.name(), def.version());
            entities = entityIds.size();
            int counted = 0;
            for (String entity : entityIds) {
                Map<String, FeatureValue> values = reads.resolve(def, entity, now, false);
                for (FeatureValue fv : values.values()) {
                    if (fv.status() == ValueStatus.PRESENT) {
                        counted++;
                    }
                }
            }
            present = counted;
        } finally {
            state.lock().readLock().unlock();
        }
        int featureCount = def.features().size();
        int total = entities * featureCount;
        double ratio = total == 0 ? 0.0 : (double) present / total;
        return new CoverageReport(def.name(), def.version(), now, entities, featureCount,
                present, total, ratio);
    }
}
