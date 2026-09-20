package com.github.lechandonga.featurestorejava.model;

import java.util.List;

/**
 * Freshness observation for one feature set at a given clock time.
 */
public record FreshnessReport(
        String featureSet,
        int version,
        long observedAt,
        Long ttlMillis,
        boolean fresh,
        List<StaleEntity> staleEntities
) {
}
