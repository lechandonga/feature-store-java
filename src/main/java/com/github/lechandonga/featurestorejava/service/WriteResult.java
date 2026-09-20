package com.github.lechandonga.featurestorejava.service;

import java.util.List;
import com.github.lechandonga.featurestorejava.domain.RecordOrigin;
import com.github.lechandonga.featurestorejava.domain.WriteViolation;

/** Result of a write/backfill batch. A rejected batch has acceptedRows == 0 and changed nothing. */
public record WriteResult(
        String batchId,
        boolean accepted,
        RecordOrigin origin,
        int acceptedRows,
        long committedAtMs,
        List<WriteViolation> violations) {

    public static WriteResult rejected(String batchId, RecordOrigin origin, List<WriteViolation> violations) {
        return new WriteResult(batchId, false, origin, 0, 0L, List.copyOf(violations));
    }
}
