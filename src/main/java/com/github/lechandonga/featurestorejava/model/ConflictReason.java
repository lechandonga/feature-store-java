package com.github.lechandonga.featurestorejava.model;

/**
 * Distinguishable reasons why a new feature set version is rejected.
 */
public enum ConflictReason {
    FEATURE_SET_NOT_FOUND,
    VERSION_NOT_NEXT,
    ENTITY_KEY_CHANGED,
    FEATURE_REMOVED,
    FEATURE_RENAMED_OR_REORDERED,
    FEATURE_TYPE_CHANGED,
    REQUIRED_FLAG_CHANGED,
    DEFAULT_VALUE_CHANGED,
    MISSING_POLICY_CHANGED,
    TTL_SHORTENED,
    DUPLICATE_FEATURE_NAME,
    ALREADY_EXISTS,
    INVALID_DEFINITION
}
