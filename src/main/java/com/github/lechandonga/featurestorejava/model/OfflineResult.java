package com.github.lechandonga.featurestorejava.model;

import java.util.Map;

/**
 * Point-in-time-correct historical lookup result.
 * Only values with eventTime <= asOfTime AND ingestTime <= asOfTime are visible.
 */
public record OfflineResult(
        String featureSet,
        int version,
        String entity,
        long asOfTime,
        Map<String, FeatureValue> features
) {
}
