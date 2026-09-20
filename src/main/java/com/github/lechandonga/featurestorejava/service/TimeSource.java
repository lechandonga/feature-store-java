package com.github.lechandonga.featurestorejava.service;

/**
 * Wall-clock time source, overridable in tests for reproducible as-of semantics.
 */
public class TimeSource {
    public long now() {
        return System.currentTimeMillis();
    }
}
