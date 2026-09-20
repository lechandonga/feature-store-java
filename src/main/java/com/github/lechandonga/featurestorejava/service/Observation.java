package com.github.lechandonga.featurestorejava.service;

import java.util.List;

/** A point-in-time consistency observation: online read captured together with its time point. */
public record Observation(
        String entityId,
        long asOfMs,
        List<String> featureNames,
        List<Integer> versions) {
}
