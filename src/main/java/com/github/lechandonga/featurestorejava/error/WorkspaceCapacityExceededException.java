package com.github.lechandonga.featurestorejava.error;

/**
 * Thrown when a register / write / backfill would push a workspace past one of
 * its configured capacity limits. The whole batch is rejected before any state
 * change, so existing data and versions are never partially modified.
 */
public class WorkspaceCapacityExceededException extends RuntimeException {

    private final String workspace;
    private final String featureSet;
    private final String dimension;
    private final long limit;
    private final long current;
    private final long requested;

    public WorkspaceCapacityExceededException(String workspace, String featureSet,
                                              String dimension, long limit,
                                              long current, long requested) {
        super("workspace '" + workspace + "' capacity exceeded on " + dimension
                + ": limit=" + limit + ", current=" + current
                + ", requested=" + requested + "; the whole batch was rejected");
        this.workspace = workspace;
        this.featureSet = featureSet;
        this.dimension = dimension;
        this.limit = limit;
        this.current = current;
        this.requested = requested;
    }

    public String getWorkspace() {
        return workspace;
    }

    public String getFeatureSet() {
        return featureSet;
    }

    public String getDimension() {
        return dimension;
    }

    public long getLimit() {
        return limit;
    }

    public long getCurrent() {
        return current;
    }

    public long getRequested() {
        return requested;
    }
}
