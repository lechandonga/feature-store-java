package com.github.lechandonga.featurestorejava.model;

import java.util.Map;

/**
 * Structured audit log entry. Every acceptance/rejection decision records
 * workspace, entity, time point and the reason/evidence the decision was
 * based on. Audit trails are independent per workspace.
 */
public record AuditEntry(
        long timestamp,
        String workspace,
        String action,
        String featureSet,
        Integer version,
        String entity,
        Long eventTime,
        String decision,
        String reason,
        Map<String, Object> evidence
) {
}
