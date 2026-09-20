package com.github.lechandonga.featurestorejava.model;

import java.util.List;

/**
 * Result of an online/offline equivalence check.
 */
public record ConsistencyReport(
        String featureSet,
        int version,
        int targetsChecked,
        boolean equivalent,
        List<ConsistencyIssue> issues
) {
}
