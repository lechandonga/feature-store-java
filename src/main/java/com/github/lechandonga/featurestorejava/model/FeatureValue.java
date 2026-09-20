package com.github.lechandonga.featurestorejava.model;

/**
 * A single feature value as returned to a reader, with provenance.
 *
 * @param value      resolved value, possibly null
 * @param status     PRESENT / DEFAULTED / MISSING / EXPIRED / REJECTED
 * @param eventTime  event time of the point used, or null
 * @param ingestTime ingest time of the point used, or null
 * @param pointId    id of the point used, or null
 */
public record FeatureValue(
        Object value,
        ValueStatus status,
        Long eventTime,
        Long ingestTime,
        String pointId
) {
}
