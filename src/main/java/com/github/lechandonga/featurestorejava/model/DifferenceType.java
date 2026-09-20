package com.github.lechandonga.featurestorejava.model;

/**
 * Classification of an online vs offline difference under identical version.
 */
public enum DifferenceType {
    VALUE_MISMATCH,
    STATUS_MISMATCH,
    POINT_MISMATCH,
    ONLY_ONLINE,
    ONLY_OFFLINE
}
