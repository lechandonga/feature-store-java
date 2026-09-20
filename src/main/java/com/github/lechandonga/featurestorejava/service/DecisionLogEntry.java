package com.github.lechandonga.featurestorejava.service;

/** Structured audit entry containing entity, time point and the basis for the decision. */
public record DecisionLogEntry(
        long logTsMs,
        String action,
        String featureName,
        Integer version,
        String entityId,
        Long eventTsMs,
        Long asOfMs,
        String decision,
        String basis) {
}
