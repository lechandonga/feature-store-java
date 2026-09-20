package com.github.lechandonga.featurestorejava.model;

import java.util.Map;

/**
 * One backfill correction for an existing stored point.
 *
 * @param targetPointId id of the point being corrected (as returned by writes)
 * @param entity        entity id (validated against the stored point)
 * @param eventTime     original event time (validated; backfill never rewrites time)
 * @param correctedValues full replacement feature map for that event time
 * @param reason        human-readable reason for the correction
 */
public record Correction(
        String targetPointId,
        String entity,
        long eventTime,
        Map<String, Object> correctedValues,
        String reason
) {
}
