package com.github.lechandonga.featurestorejava.model;

/**
 * Per-workspace capacity limits. A null limit means the dimension is unlimited.
 *
 * @param maxFeatureSets maximum number of distinct feature set names
 * @param maxPoints      maximum total number of persisted historical points
 *                       (all feature sets and versions combined)
 */
public record WorkspaceLimits(Integer maxFeatureSets, Long maxPoints) {
}
