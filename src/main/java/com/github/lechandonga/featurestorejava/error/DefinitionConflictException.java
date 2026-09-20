package com.github.lechandonga.featurestorejava.error;

import java.util.List;

/**
 * Thrown when registering a new version conflicts with the previous one.
 * The whole registration is rejected; no new version is created.
 */
public class DefinitionConflictException extends RuntimeException {

    private final String featureSet;
    private final List<ConflictDetail> conflicts;

    public DefinitionConflictException(String featureSet, List<ConflictDetail> conflicts) {
        super("feature set '" + featureSet + "' has " + conflicts.size() + " definition conflict(s)");
        this.featureSet = featureSet;
        this.conflicts = List.copyOf(conflicts);
    }

    public String getFeatureSet() {
        return featureSet;
    }

    public List<ConflictDetail> getConflicts() {
        return conflicts;
    }
}
