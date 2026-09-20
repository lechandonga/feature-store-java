package com.github.lechandonga.featurestorejava.domain;

/**
 * One stored feature value for an entity at an event time, with audit metadata.
 * eventTsMs     business/event time of the observation
 * ingestTsMs    wall-clock time at which the value entered the store
 * revisionTsMs  wall-clock commit time (ingest for normal writes; backfill commit for corrections)
 */
public record FeatureRecord(
        String featureName,
        int version,
        String entityId,
        long eventTsMs,
        Object value,
        long ingestTsMs,
        long revisionTsMs,
        RecordOrigin origin) {
}
