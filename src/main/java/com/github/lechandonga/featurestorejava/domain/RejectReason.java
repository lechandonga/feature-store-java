package com.github.lechandonga.featurestorejava.domain;

/** Distinguishable reasons for rejecting a write/backfill row or batch. */
public enum RejectReason {
    UNKNOWN_FEATURE,
    VERSION_NOT_FOUND,
    MISSING_ENTITY_ID,
    MISSING_EVENT_TIME,
    MISSING_VALUE,
    TYPE_MISMATCH,
    FUTURE_EVENT_TIME,
    OUT_OF_ORDER,
    DUPLICATE_WRITE,
    INVALID_TIME,
    INVALID_BATCH
}
