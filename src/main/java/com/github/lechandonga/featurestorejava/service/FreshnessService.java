package com.github.lechandonga.featurestorejava.service;

import java.util.Comparator;
import java.util.List;
import com.github.lechandonga.featurestorejava.domain.ConflictReason;
import com.github.lechandonga.featurestorejava.domain.FeatureDefinition;
import com.github.lechandonga.featurestorejava.domain.FeatureRecord;
import com.github.lechandonga.featurestorejava.exception.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.springframework.stereotype.Service;

/**
 * Freshness and coverage observation. For every requested feature it reports the latest available
 * value's age against its registered ttl; stale or missing features are counted and surfaced
 * explicitly (never silently replaced by defaults).
 */
@Service
public class FreshnessService {

    private final FeatureRegistry registry;
    private final FeatureStorage storage;
    private final DecisionLogger logger;

    public FreshnessService(FeatureRegistry registry, FeatureStorage storage, DecisionLogger logger) {
        this.registry = registry;
        this.storage = storage;
        this.logger = logger;
    }

    public FreshnessReport report(String entityId, List<String> featureNames, List<Integer> versions) {
        long now = System.currentTimeMillis();
        List<FreshnessReport.Item> items = new java.util.ArrayList<>();
        int stale = 0;
        int missing = 0;

        for (int i = 0; i < featureNames.size(); i++) {
            String name = featureNames.get(i);
            Integer requestedVersion = versions == null || i >= versions.size() ? null : versions.get(i);
            FeatureDefinition def = requestedVersion == null
                    ? registry.getLatest(name)
                    : registry.get(name, requestedVersion);
            if (def == null) {
                throw new DefinitionConflictException(ConflictReason.VERSION_NOT_FOUND,
                        "feature=" + name + " version=" + requestedVersion + " not registered");
            }

            List<FeatureRecord> records = storage.scan(def.name(), def.version(), entityId);
            FeatureRecord latest = records.stream()
                    .max(Comparator.comparingLong(FeatureRecord::eventTsMs)
                            .thenComparingLong(FeatureRecord::revisionTsMs))
                    .orElse(null);

            if (latest == null) {
                missing++;
                items.add(new FreshnessReport.Item(name, def.version(), FreshnessReport.Freshness.MISSING,
                        null, null, def.ttlMs(), "no value has ever been written for entity " + entityId));
                logger.log("freshness", name, def.version(), entityId, null, now, "MISSING",
                        "no stored value; coverage gap reported, not defaulted");
                continue;
            }

            long age = now - latest.eventTsMs();
            if (def.ttlMs() <= 0) {
                items.add(new FreshnessReport.Item(name, def.version(),
                        FreshnessReport.Freshness.NOT_TRACKED, latest.eventTsMs(), age, 0L,
                        "ttl not configured; latest value age=" + age + "ms"));
            } else if (age > def.ttlMs()) {
                stale++;
                items.add(new FreshnessReport.Item(name, def.version(),
                        FreshnessReport.Freshness.STALE, latest.eventTsMs(), age, def.ttlMs(),
                        "age " + age + "ms exceeds ttl " + def.ttlMs() + "ms"));
                logger.log("freshness", name, def.version(), entityId, latest.eventTsMs(), now, "STALE",
                        "age=" + age + "ms > ttl=" + def.ttlMs() + "ms");
            } else {
                items.add(new FreshnessReport.Item(name, def.version(),
                        FreshnessReport.Freshness.FRESH, latest.eventTsMs(), age, def.ttlMs(),
                        "age " + age + "ms within ttl " + def.ttlMs() + "ms"));
            }
        }

        double coverage = featureNames.isEmpty() ? 1.0
                : (featureNames.size() - missing) / (double) featureNames.size();
        return new FreshnessReport(now, entityId, featureNames.size(), stale, missing,
                coverage, List.copyOf(items));
    }
}
