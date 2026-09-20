package com.github.lechandonga.featurestorejava.service;

/**
 * One input row of a write/backfill request.
 *
 * @param version     explicit feature version; null means latest registered version
 * @param ingestTsMs  wall-clock time at which the source observation entered the pipeline;
 *                    null means "now". Point-in-time offline reads use it as the visibility cutoff,
 *                    so backtests over historical data can be reproduced faithfully.
 */
public record RowInput(String featureName, Integer version, String entityId, Long eventTsMs,
                       Object value, Long ingestTsMs) {

    public RowInput(String featureName, Integer version, String entityId, Long eventTsMs, Object value) {
        this(featureName, version, entityId, eventTsMs, value, null);
    }
}
