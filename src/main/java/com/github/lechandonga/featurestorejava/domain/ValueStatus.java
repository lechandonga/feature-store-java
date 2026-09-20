package com.github.lechandonga.featurestorejava.domain;

/**
 * Status of a served feature value. Never silently returns a default:
 * defaulted / missing / expired values are always explicitly marked.
 */
public enum ValueStatus {
    RECORDED, DEFAULTED, MISSING, MISSING_ERROR, EXPIRED
}
