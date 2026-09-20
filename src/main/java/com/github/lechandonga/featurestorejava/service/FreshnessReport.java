package com.github.lechandonga.featurestorejava.service;

import java.util.List;

/** Freshness and coverage observation. Stale or missing features are reported, never hidden. */
public record FreshnessReport(
        long observedAtMs,
        String entityId,
        int checkedFeatures,
        int staleCount,
        int missingCount,
        double coverageRatio,
        List<Item> items) {

    public enum Freshness { FRESH, STALE, MISSING, NOT_TRACKED }

    public record Item(
            String featureName,
            int version,
            Freshness freshness,
            Long latestEventTsMs,
            Long ageMs,
            Long ttlMs,
            String detail) {
    }
}
