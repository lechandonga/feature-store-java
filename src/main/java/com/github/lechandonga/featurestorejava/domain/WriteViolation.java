package com.github.lechandonga.featurestorejava.domain;

/** A single validation failure found in a write/backfill row. */
public record WriteViolation(
        int rowIndex,
        String entityId,
        Long eventTsMs,
        RejectReason reason,
        String detail) {
}
