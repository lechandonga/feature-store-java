package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.CoverageReport;
import com.github.lechandonga.featurestorejava.model.FreshnessReport;

/**
 * Freshness (TTL) and coverage observation. Staleness is reported explicitly.
 */
public class ObservabilityService {

    public ObservabilityService(StoreState state, FeatureRegistry registry, ReadService reads, TimeSource time) {
        // implemented later
    }

    public FreshnessReport freshness(String featureSet, Integer version, Long observedAt) {
        return null; // implemented later
    }

    public CoverageReport coverage(String featureSet, Integer version, Long observedAt) {
        return null; // implemented later
    }
}
