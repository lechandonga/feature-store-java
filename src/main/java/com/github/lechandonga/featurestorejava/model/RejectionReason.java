package com.github.lechandonga.featurestorejava.model;

/**
 * Distinguishable reasons a write batch is rejected.
 */
public enum RejectionReason {
    FEATURE_SET_NOT_FOUND,
    VERSION_MISMATCH,
    EMPTY_BATCH,
    ROW_MISSING_ENTITY_KEY,
    UNKNOWN_FIELD,
    MISSING_REQUIRED_FIELD,
    TYPE_MISMATCH,
    DUPLICATE_WITHIN_BATCH,
    REVERSE_ORDER_WITHIN_BATCH,
    DUPLICATE_ALREADY_STORED,
    SAME_TIME_DIFFERENT_VALUE,
    INVALID_DEFAULT_TYPE
}
