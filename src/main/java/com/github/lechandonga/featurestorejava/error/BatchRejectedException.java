package com.github.lechandonga.featurestorejava.error;

import com.github.lechandonga.featurestorejava.model.RowRejection;

import java.util.List;

/**
 * Thrown when a write or backfill batch fails validation.
 * The batch is rejected atomically: no row becomes visible.
 */
public class BatchRejectedException extends RuntimeException {

    private final String featureSet;
    private final Integer version;
    private final List<RowRejection> rejections;

    public BatchRejectedException(String featureSet, Integer version, List<RowRejection> rejections) {
        super("batch for '" + featureSet + "' v" + version + " rejected with "
                + rejections.size() + " violation(s)");
        this.featureSet = featureSet;
        this.version = version;
        this.rejections = List.copyOf(rejections);
    }

    public String getFeatureSet() {
        return featureSet;
    }

    public Integer getVersion() {
        return version;
    }

    public List<RowRejection> getRejections() {
        return rejections;
    }
}
