package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.ConsistencyReport;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;

import java.util.List;

/**
 * Equivalence checks between online and offline paths under one version.
 */
public class ConsistencyService {

    public ConsistencyService(ReadService reads, FeatureRegistry registry) {
        // implemented later
    }

    public ConsistencyReport check(String featureSet, Integer version, List<EntityAtPoint> targets) {
        return null; // implemented later
    }
}
