package com.github.lechandonga.featurestorejava.domain;

/**
 * Immutable feature definition for one explicit version.
 * Version semantics: valueType and missingPolicy are immutable across versions;
 * only description and defaultValue (must remain the same type) may evolve compatibly.
 *
 * @param ttlMs freshness time-to-live in milliseconds; a value older than ttl is served as EXPIRED.
 *               0 means freshness checking is disabled for the feature.
 */
public record FeatureDefinition(
        String name,
        int version,
        ValueType valueType,
        MissingPolicy missingPolicy,
        Object defaultValue,
        String description,
        long ttlMs,
        long createdAtMs) {
}
