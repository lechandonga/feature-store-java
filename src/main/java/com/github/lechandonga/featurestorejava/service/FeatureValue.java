package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.domain.RecordOrigin;
import com.github.lechandonga.featurestorejava.domain.ValueStatus;

/**
 * A served feature value with full provenance. status is never ambiguous:
 * DEFAULTED / MISSING / MISSING_ERROR / EXPIRED are reported explicitly rather than
 * silently returning the default. explanation states the decision basis.
 */
public record FeatureValue(
        String featureName,
        int version,
        String entityId,
        long asOfMs,
        Object value,
        ValueStatus status,
        Long eventTsMs,
        Long ingestTsMs,
        Long revisionTsMs,
        RecordOrigin origin,
        String explanation) {
}
