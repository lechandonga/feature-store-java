package com.github.lechandonga.featurestorejava.model;

import java.util.List;

/**
 * Result of an atomic backfill commit.
 *
 * @param appliedCount number of corrections applied
 * @param newPointIds  ids of the newly appended corrected points
 */
public record BackfillResult(int appliedCount, List<String> newPointIds) {
}
