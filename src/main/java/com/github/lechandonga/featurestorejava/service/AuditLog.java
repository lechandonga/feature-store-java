package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.AuditEntry;

import java.util.List;
import java.util.Map;

/**
 * Structured decision audit trail. In addition to normal application logging,
 * recent entries are kept in a bounded ring buffer and exposed for verification.
 */
public class AuditLog {

    public void record(String action, String featureSet, Integer version,
                       String entity, Long eventTime, String decision,
                       String reason, Map<String, Object> evidence) {
        // implemented later
    }

    public List<AuditEntry> recent(int limit) {
        return List.of(); // implemented later
    }

    public List<AuditEntry> forEntity(String featureSet, String entity, int limit) {
        return List.of(); // implemented later
    }
}
