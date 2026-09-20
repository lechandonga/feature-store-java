package com.github.lechandonga.featurestorejava.model;

import java.util.Map;

/**
 * One stored feature observation in the offline (bitemporal) store.
 *
 * @param id          unique point id
 * @param featureSet  feature set name
 * @param version     definition version under which it was validated
 * @param entity      entity id
 * @param eventTime   business event time
 * @param ingestTime  wall-clock time at which this value became visible
 * @param values      feature map (only declared features)
 * @param supersedes  id of the point corrected by this one, or null
 * @param source      WRITE or BACKFILL
 * @param reason      correction reason, or null for normal writes
 */
public record StoredPoint(
        String id,
        String featureSet,
        int version,
        String entity,
        long eventTime,
        long ingestTime,
        Map<String, Object> values,
        String supersedes,
        String source,
        String reason
) {
}
