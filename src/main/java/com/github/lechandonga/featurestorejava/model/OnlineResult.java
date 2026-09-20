package com.github.lechandonga.featurestorejava.model;

import java.util.Map;

/**
 * Online lookup result for one entity under one feature set version.
 */
public record OnlineResult(
        String featureSet,
        int version,
        String entity,
        long asOfTime,
        Map<String, FeatureValue> features
) {
}
