package com.github.lechandonga.featurestorejava.model;

/**
 * Coverage observation: fraction of (entity, feature) cells currently present.
 * DEFAULTED/MISSING/EXPIRED cells count as not covered; nothing is hidden.
 */
public record CoverageReport(
        String featureSet,
        int version,
        long observedAt,
        int entityCount,
        int featureCount,
        int presentCells,
        int totalCells,
        double coverageRatio
) {
}
