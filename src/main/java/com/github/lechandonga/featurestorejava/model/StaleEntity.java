package com.github.lechandonga.featurestorejava.model;

/**
 * An entity whose latest visible value is older than the feature set TTL.
 */
public record StaleEntity(String entity, long latestEventTime, long ageMillis) {
}
