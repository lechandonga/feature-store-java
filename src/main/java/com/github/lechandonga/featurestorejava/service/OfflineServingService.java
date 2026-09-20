package com.github.lechandonga.featurestorejava.service;

import java.util.ArrayList;
import java.util.List;
import com.github.lechandonga.featurestorejava.domain.FeatureDefinition;
import com.github.lechandonga.featurestorejava.domain.ConflictReason;
import com.github.lechandonga.featurestorejava.exception.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.springframework.stereotype.Service;

/**
 * Point-in-time offline reads for training / backtesting: fixed to AS_OF mode.
 * The resolved (explicit) versions are used, so a materialized training set is reproducible
 * regardless of later registrations or backfills.
 */
@Service
public class OfflineServingService {

    private final FeatureRegistry registry;
    private final FeatureLookupService lookup;
    private final FeatureStorage storage;

    public OfflineServingService(FeatureRegistry registry, FeatureLookupService lookup,
                                 FeatureStorage storage) {
        this.registry = registry;
        this.lookup = lookup;
        this.storage = storage;
    }

    public List<FeatureValue> historical(Observation observation) {
        Object snapshotHandle = storage.captureSnapshot();
        List<FeatureValue> result = new ArrayList<>();
        List<String> names = observation.featureNames();
        List<Integer> versions = observation.versions();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Integer requestedVersion = versions == null || i >= versions.size() ? null : versions.get(i);
            FeatureDefinition def = requestedVersion == null
                    ? registry.getLatest(name)
                    : registry.get(name, requestedVersion);
            if (def == null) {
                throw new DefinitionConflictException(ConflictReason.VERSION_NOT_FOUND,
                        "feature=" + name + " version=" + requestedVersion + " not registered");
            }
            result.add(lookup.lookup(def, observation.entityId(), observation.asOfMs(),
                    FeatureLookupService.LookupMode.AS_OF, snapshotHandle));
        }
        return result;
    }
}
