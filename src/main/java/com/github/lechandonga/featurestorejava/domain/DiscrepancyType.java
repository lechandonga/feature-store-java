package com.github.lechandonga.featurestorejava.domain;

/** Classification of an online/offline consistency difference. */
public enum DiscrepancyType {
    MATCH,
    VALUE_MISMATCH,
    PRESENCE_MISMATCH,
    STATUS_MISMATCH,
    VERSION_MISMATCH
}
