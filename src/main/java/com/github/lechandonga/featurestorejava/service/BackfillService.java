package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.BackfillResult;

/**
 * Backfill / recompute. Corrections append bitemporal points; the online view
 * switches atomically per batch, never exposing a half-recomputed state.
 */
public class BackfillService {

    public BackfillService(StoreState state, FeatureRegistry registry, AuditLog audit, TimeSource time) {
        // implemented later
    }

    public BackfillResult backfill(BackfillRequest request) {
        return null; // implemented later
    }
}
