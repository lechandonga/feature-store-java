package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.AuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured decision audit trail.
 *
 * <p>Every acceptance/rejection decision is (a) emitted through SLF4J with a
 * stable, greppable layout and (b) retained in a bounded ring buffer exposed
 * via the ops API and tests. Each entry carries the feature set, version,
 * entity, event time point and the explicit reason/evidence for the decision,
 * so lookups and rejections stay explainable and reproducible.
 */
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger("feature-store-audit");
    private static final int CAPACITY = 2_000;

    private final LinkedHashMap<Long, AuditEntry> ring = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, AuditEntry> eldest) {
            return size() > CAPACITY;
        }
    };
    private long sequence = 0;

    public void record(String action, String featureSet, Integer version,
                       String entity, Long eventTime, String decision,
                       String reason, Map<String, Object> evidence) {
        AuditEntry entry;
        synchronized (ring) {
            long seq = ++sequence;
            entry = new AuditEntry(System.currentTimeMillis(), action, featureSet, version,
                    entity, eventTime, decision, reason,
                    evidence == null ? Map.of() : Map.copyOf(evidence));
            ring.put(seq, entry);
        }
        log.info("action={} featureSet={} version={} entity={} eventTime={} decision={} reason={} evidence={}",
                action, featureSet, version, entity, eventTime, decision, reason,
                entry.evidence());
    }

    public List<AuditEntry> recent(int limit) {
        synchronized (ring) {
            int n = Math.min(Math.max(limit, 0), ring.size());
            return ring.values().stream().skip(ring.size() - n).toList();
        }
    }

    public List<AuditEntry> forEntity(String featureSet, String entity, int limit) {
        synchronized (ring) {
            List<AuditEntry> matched = ring.values().stream()
                    .filter(e -> featureSet == null || featureSet.equals(e.featureSet()))
                    .filter(e -> entity == null || entity.equals(e.entity()))
                    .toList();
            int n = Math.min(Math.max(limit, 0), matched.size());
            return matched.stream().skip(matched.size() - n).toList();
        }
    }
}
