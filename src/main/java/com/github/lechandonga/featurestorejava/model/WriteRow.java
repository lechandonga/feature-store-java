package com.github.lechandonga.featurestorejava.model;

import java.util.Map;

/**
 * One row of a write batch.
 *
 * @param eventTime feature event time (epoch millis); determines as-of visibility
 * @param fields    feature field name -> value (entity key must also be present)
 */
public record WriteRow(long eventTime, Map<String, Object> fields) {
}
