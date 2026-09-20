package com.github.lechandonga.featurestorejava.model;

/**
 * How a missing feature value is surfaced to readers.
 * DEFAULT   -> the declared default value is returned, flagged as defaulted
 * NULL      -> null is returned, flagged as missing
 * REJECT    -> the read is reported as MISSING (never silently defaulted)
 */
public enum MissingPolicy {
    DEFAULT,
    NULL,
    REJECT
}
