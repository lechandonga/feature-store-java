package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.OfflineResult;
import com.github.lechandonga.featurestorejava.model.OnlineResult;

import java.util.List;

/**
 * Online and point-in-time-correct offline reads backed by one definition.
 */
public class ReadService {

    public ReadService(StoreState state, FeatureRegistry registry, TimeSource time) {
        // implemented later
    }

    public OnlineResult online(String featureSet, Integer version, String entity, Long asOfTime) {
        return null; // implemented later
    }

    public List<OfflineResult> offline(String featureSet, Integer version, List<EntityAtPoint> targets) {
        return List.of(); // implemented later
    }
}
