package com.github.lechandonga.featurestorejava.error;

import java.util.List;

/**
 * Thrown when registering a new version conflicts with the previous one.
 * The whole registration is rejected; no new version is created.
 */
public class DefinitionConflictException extends RuntimeException {

    private final String workspace;
    private final String featureSet;
    private final List<ConflictDetail> conflicts;

    public DefinitionConflictException(String featureSet, List<ConflictDetail> conflicts) {
        this(null, featureSet, conflicts);
    }

    public DefinitionConflictException(String workspace, String featureSet,
                                       List<ConflictDetail> conflicts) {
        super("feature set '" + featureSet + "' in workspace '" + workspace + "' has "
                + conflicts.size() + " definition conflict(s)");
        this.workspace = workspace;
        this.featureSet = featureSet;
        this.conflicts = List.copyOf(conflicts);
    }

    public String getWorkspace() {
        return workspace;
    }

    public String getFeatureSet() {
        return featureSet;
    }

    public List<ConflictDetail> getConflicts() {
        return conflicts;
    }
}
