package com.github.lechandonga.featurestorejava.model;

import java.util.List;

/**
 * Backfill / recompute request.
 *
 * <p>Each correction references a previously stored point. Existing rows are
 * never mutated in place: a new corrected, as-of-visible value is appended,
 * which keeps historical lookups reproducible and auditable.
 *
 * @param featureSet    feature set name
 * @param version       feature set version
 * @param correlationId optional idempotency key for the recompute job
 * @param corrections   corrections to append
 */
public record BackfillRequest(
        String featureSet,
        int version,
        String correlationId,
        List<Correction> corrections
) {
}
