package com.github.lechandonga.featurestorejava.error;

import com.github.lechandonga.featurestorejava.model.ConflictReason;

/**
 * One distinguishable definition-conflict for a version evolution request.
 */
public record ConflictDetail(ConflictReason reason, String feature, String detail) {
}
