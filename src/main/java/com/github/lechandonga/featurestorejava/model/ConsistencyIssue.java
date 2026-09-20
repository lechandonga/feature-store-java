package com.github.lechandonga.featurestorejava.model;

/**
 * One detected online/offline inconsistency.
 *
 * @param entity         entity id
 * @param asOfTime       lookup time
 * @param feature        feature name
 * @param type           difference classification
 * @param onlineStatus   online value status
 * @param offlineStatus  offline value status
 * @param onlineValue    online value
 * @param offlineValue   offline value
 */
public record ConsistencyIssue(
        String entity,
        long asOfTime,
        String feature,
        DifferenceType type,
        String onlineStatus,
        String offlineStatus,
        Object onlineValue,
        Object offlineValue
) {
}
