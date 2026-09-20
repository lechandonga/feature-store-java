package com.github.lechandonga.featurestorejava.service;

import java.util.ArrayList;
import java.util.List;
import com.github.lechandonga.featurestorejava.domain.FeatureDefinition;
import com.github.lechandonga.featurestorejava.exception.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.domain.ConflictReason;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.springframework.stereotype.Service;

/** Online feature reads: uses the same lookup engine as offline reads, fixed to ONLINE mode. */
@Service
public class OnlineServingService {

    private final FeatureRegistry registry;
    private final FeatureLookupService lookup;
    private final FeatureStorage storage;

    public OnlineServingService(FeatureRegistry registry, FeatureLookupService lookup,
                                FeatureStorage storage) {
        this.registry = registry;
        this.lookup = lookup;
        this.storage = storage;
    }

    public List<FeatureValue> read(String entityId, List<String> featureNames, List<Integer> versions) {
        long asOf = System.currentTimeMillis();
        // One snapshot for the whole request: a concurrent commit cannot make feature A and
        // feature B come from two different revisions.
        Object snapshotHandle = storage.captureSnapshot();
        List<FeatureValue> result = new ArrayList<>();
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
            result.add(lookup.lookup(def, entityId, asOf, FeatureLookupService.LookupMode.ONLINE,
                    snapshotHandle));
        }
        return result;
    }
}
