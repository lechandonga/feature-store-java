package com.github.lechandonga.featurestorejava.model;

/**
 * Immutable definition of a single feature inside a feature set version.
 *
 * @param name          feature name, unique within the feature set
 * @param type          declared value type
 * @param required      whether a write row must contain this field
 * @param defaultValue  value returned under MissingPolicy.DEFAULT (may itself be null)
 * @param missingPolicy how missing values are resolved on read
 * @param description   human-readable semantics
 */
public record FeatureDef(
        String name,
        ValueType type,
        boolean required,
        Object defaultValue,
        MissingPolicy missingPolicy,
        String description
) {
}
