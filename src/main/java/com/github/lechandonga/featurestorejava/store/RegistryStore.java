package com.github.lechandonga.featurestorejava.store;

import com.github.lechandonga.featurestorejava.model.FeatureSetDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store of feature set definitions, keyed by (name, version).
 *
 * <p>It is mutated only while the global commit write-lock is held, which makes
 * the {@link java.util.concurrent.ConcurrentHashMap} internal synchronization
 * sufficient; every accessor returns an independent copy so callers can never
 * mutate registry state.
 */
public class RegistryStore {

    private final ConcurrentMap<String, NavigableMap<Integer, FeatureSetDef>> byName =
            new ConcurrentHashMap<>();

    public void put(FeatureSetDef def) {
        byName.computeIfAbsent(def.name(), k -> new TreeMap<>()).put(def.version(), def);
    }

    public FeatureSetDef getLatest(String name) {
        NavigableMap<Integer, FeatureSetDef> versions = byName.get(name);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.lastEntry().getValue();
    }

    public FeatureSetDef getVersion(String name, int version) {
        NavigableMap<Integer, FeatureSetDef> versions = byName.get(name);
        return versions == null ? null : versions.get(version);
    }

    public List<FeatureSetDef> versions(String name) {
        NavigableMap<Integer, FeatureSetDef> versions = byName.get(name);
        if (versions == null) {
            return List.of();
        }
        return List.copyOf(versions.values());
    }

    /** Number of distinct registered feature set names in this workspace. */
    public int featureSetCount() {
        return byName.size();
    }

    public List<FeatureSetDef> allLatest() {        List<FeatureSetDef> latest = new ArrayList<>();
        for (Map.Entry<String, NavigableMap<Integer, FeatureSetDef>> e : byName.entrySet()) {
            if (!e.getValue().isEmpty()) {
                latest.add(e.getValue().lastEntry().getValue());
            }
        }
        latest.sort(Comparator.comparing(FeatureSetDef::name));
        return List.copyOf(latest);
    }
}
