package com.github.lechandonga.featurestorejava.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown when an operation would exceed a workspace capacity limit.
 *
 * <p>The whole operation is rejected before any mutation, so existing data and
 * versions are never partially changed. The exception carries the workspace,
 * the offending feature set/entity/time point when applicable, both the limit
 * and the projected usage, and the explicit basis of the decision.
 */
public class CapacityExceededException extends RuntimeException {

    private final String workspace;
    private final String featureSet;
    private final Integer version;
    private final String dimension;
    private final long limit;
    private final long currentUsage;
    private final long requested;
    private final Map<String, Object> context;

    public CapacityExceededException(String workspace, String featureSet, Integer version,
                                     String dimension, long limit, long currentUsage,
                                     long requested, Map<String, Object> context) {
        super("workspace '" + workspace + "' capacity exceeded on dimension " + dimension
                + ": limit=" + limit + ", currentUsage=" + currentUsage
                + ", requested=" + requested
                + (featureSet != null ? ", featureSet=" + featureSet : ""));
        this.workspace = workspace;
        this.featureSet = featureSet;
        this.version = version;
        this.dimension = dimension;
        this.limit = limit;
        this.currentUsage = currentUsage;
        this.requested = requested;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public String getWorkspace() {
        return workspace;
    }

    public String getFeatureSet() {
        return featureSet;
    }

    public Integer getVersion() {
        return version;
    }

    public String getDimension() {
        return dimension;
    }

    public long getLimit() {
        return limit;
    }

    public long getCurrentUsage() {
        return currentUsage;
    }

    public long getRequested() {
        return requested;
    }

    public Map<String, Object> getContext() {
        Map<String, Object> out = new LinkedHashMap<>(context);
        out.put("dimension", dimension);
        out.put("limit", limit);
        out.put("currentUsage", currentUsage);
        out.put("requested", requested);
        out.put("basis", "currentUsage + requested must be <= limit for the batch "
                + "to commit as a whole; no row was applied");
        return out;
    }
}
