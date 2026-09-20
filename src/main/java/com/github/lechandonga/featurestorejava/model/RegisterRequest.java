package com.github.lechandonga.featurestorejava.model;

import java.util.List;

/**
 * Request body for registering a new feature set (version 1).
 */
public record RegisterRequest(
        String name,
        String entityKey,
        Long ttlMillis,
        List<FeatureDef> features,
        String description
) {
}
