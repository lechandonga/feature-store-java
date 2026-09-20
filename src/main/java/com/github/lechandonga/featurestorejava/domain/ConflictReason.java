package com.github.lechandonga.featurestorejava.domain;

/** Distinguishable reasons for rejecting a feature definition / version evolution. */
public enum ConflictReason {
    FEATURE_NOT_FOUND,
    VERSION_NOT_FOUND,
    VERSION_ALREADY_EXISTS,
    TYPE_CHANGED,
    MISSING_POLICY_CHANGED,
    DEFAULT_VALUE_TYPE_MISMATCH,
    INVALID_DEFINITION
}
