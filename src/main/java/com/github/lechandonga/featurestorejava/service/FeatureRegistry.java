package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;

import java.util.List;

/**
 * Feature registration and version evolution.
 */
public class FeatureRegistry {

    public FeatureRegistry(StoreState state, AuditLog audit, TimeSource time) {
        // implemented later
    }

    public FeatureSetDef register(RegisterRequest request) {
        return null; // implemented later
    }

    public FeatureSetDef evolve(String name, List<com.github.lechandonga.featurestorejava.model.FeatureDef> features,
                                Long ttlMillis) {
        return null; // implemented later
    }

    public FeatureSetDef requireVersion(String name, Integer version) {
        return null; // implemented later
    }
}
