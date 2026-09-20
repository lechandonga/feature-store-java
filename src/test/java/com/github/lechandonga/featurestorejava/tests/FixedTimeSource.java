package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.service.TimeSource;

/**
 * Deterministic, externally controlled clock for reproducible as-of semantics.
 */
public class FixedTimeSource extends TimeSource {
    private long t;

    public FixedTimeSource(long start) {
        this.t = start;
    }

    @Override
    public long now() {
        return t;
    }

    public void set(long t) {
        this.t = t;
    }

    public void advance(long deltaMillis) {
        this.t += deltaMillis;
    }
}
