package com.github.lechandonga.featurestorejava.model;

import java.util.List;

/**
 * Result of a successful batch commit.
 *
 * @param acceptedRows number of rows that became visible
 * @param pointIds     storage point ids generated for the accepted rows
 */
public record WriteResult(int acceptedRows, List<String> pointIds) {
}
