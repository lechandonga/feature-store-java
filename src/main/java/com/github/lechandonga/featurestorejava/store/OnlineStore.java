package com.github.lechandonga.featurestorejava.store;

import com.github.lechandonga.featurestorejava.model.StoredPoint;

import java.util.List;

/**
 * Online store: per (featureSet, version, entity) an immutable point chain.
 * A whole batch replaces the affected entity chains atomically (copy-on-write),
 * so a reader can never observe a half-applied recompute.
 */
public class OnlineStore {

    /** Replace the point chains of every entity touched by the batch atomically. */
    public void applyBatch(String featureSet, int version, List<StoredPoint> points) {
        // implemented later
    }

    public List<StoredPoint> chain(String featureSet, int version, String entity) {
        return List.of(); // implemented later
    }

    public List<String> entities(String featureSet, int version) {
        return List.of(); // implemented later
    }
}
