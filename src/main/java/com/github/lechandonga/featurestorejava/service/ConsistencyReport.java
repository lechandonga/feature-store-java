package com.github.lechandonga.featurestorejava.service;

import java.util.List;
import com.github.lechandonga.featurestorejava.domain.DiscrepancyType;

/** Online/offline consistency report for one observation. */
public record ConsistencyReport(
        Status status,
        long checkedAtMs,
        Observation observation,
        List<Item> items) {

    public enum Status { MATCH, MISMATCH, ERROR }

    /** Per-feature comparison of the online value vs. the point-in-time (offline) value. */
    public record Item(
            String featureName,
            int version,
            FeatureValue online,
            FeatureValue pointInTime,
            DiscrepancyType discrepancy,
            String detail) {
    }
}
