package com.github.lechandonga.featurestorejava.model;

import java.util.Map;

/**
 * One offending row inside a rejected write batch.
 */
public record RowRejection(
        int rowIndex,
        String entity,
        Long eventTime,
        RejectionReason reason,
        String detail,
        Map<String, Object> evidence
) {
}
