package com.github.lechandonga.featurestorejava.store;

import com.github.lechandonga.featurestorejava.model.FeatureSetDef;

import java.util.List;

/**
 * In-memory store of feature set definitions, keyed by (name, version).
 * Mutated only while the global commit lock is held.
 */
public class RegistryStore {

    public void put(FeatureSetDef def) {
        // implemented later
    }

    public FeatureSetDef getLatest(String name) {
        return null; // implemented later
    }

    public FeatureSetDef getVersion(String name, int version) {
        return null; // implemented later
    }

    public List<FeatureSetDef> versions(String name) {
        return List.of(); // implemented later
    }

    public List<FeatureSetDef> allLatest() {
        return List.of(); // implemented later
    }
}
