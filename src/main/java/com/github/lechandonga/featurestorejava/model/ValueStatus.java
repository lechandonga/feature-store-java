package com.github.lechandonga.featurestorejava.model;

/**
 * Provenance status of a feature value returned by a read.
 * Never silently defaults: DEFAULTED/MISSING/EXPIRED are always explicit.
 */
public enum ValueStatus {
    PRESENT,
    DEFAULTED,
    MISSING,
    EXPIRED,
    REJECTED
}
