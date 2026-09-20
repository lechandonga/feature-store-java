package com.github.lechandonga.featurestorejava.store;

import com.github.lechandonga.featurestorejava.model.StoredPoint;

import java.util.List;

/**
 * Append-only offline (historical) store. Points are never updated in place;
 * corrections append new points that supersede older ones.
 */
public class OfflineStore {

    public void append(StoredPoint point) {
        // implemented later
    }

    public StoredPoint getPoint(String pointId) {
        return null; // implemented later
    }

    public List<StoredPoint> forFeatureSet(String featureSet, int version) {
        return List.of(); // implemented later
    }

    public List<String> entities(String featureSet, int version) {
        return List.of(); // implemented later
    }
}
