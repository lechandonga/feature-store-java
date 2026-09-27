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
 * via the ops API and tests. Each entry carries the workspace, feature set,
 * version, entity, event time point and the explicit reason/evidence for the
 * decision, so lookups and rejections stay explainable and reproducible.
 * Entries can be filtered per workspace so tenants only see their own trail.
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

    /** Record into the default workspace (pre-workspace call sites). */
    public void record(String action, String featureSet, Integer version,
                       String entity, Long eventTime, String decision,
                       String reason, Map<String, Object> evidence) {
        record(StoreState.DEFAULT_WORKSPACE, action, featureSet, version, entity,
                eventTime, decision, reason, evidence);
    }

    public void record(String workspace, String action, String featureSet, Integer version,
                       String entity, Long eventTime, String decision,
                       String reason, Map<String, Object> evidence) {
        String ws = StoreState.normalizeWorkspace(workspace);
        AuditEntry entry;
        synchronized (ring) {
            long seq = ++sequence;
            entry = new AuditEntry(System.currentTimeMillis(), ws, action, featureSet,
                    version, entity, eventTime, decision, reason,
                    evidence == null ? Map.of() : Map.copyOf(evidence));
            ring.put(seq, entry);
        }
        log.info("workspace={} action={} featureSet={} version={} entity={} eventTime={} decision={} reason={} evidence={}",
                ws, action, featureSet, version, entity, eventTime, decision, reason,
                entry.evidence());
    }

    public List<AuditEntry> recent(int limit) {
        synchronized (ring) {
            int n = Math.min(Math.max(limit, 0), ring.size());
            return ring.values().stream().skip(ring.size() - n).toList();
        }
    }

    /** Most recent entries of one workspace only. */
    public List<AuditEntry> recent(String workspace, int limit) {
        String ws = StoreState.normalizeWorkspace(workspace);
        synchronized (ring) {
            List<AuditEntry> matched = ring.values().stream()
                    .filter(e -> ws.equals(e.workspace()))
                    .toList();
            int n = Math.min(Math.max(limit, 0), matched.size());
            return matched.stream().skip(matched.size() - n).toList();
        }
    }

    public List<AuditEntry> forEntity(String featureSet, String entity, int limit) {
        return forEntity(StoreState.DEFAULT_WORKSPACE, featureSet, entity, limit);
    }

    public List<AuditEntry> forEntity(String workspace, String featureSet, String entity,
                                      int limit) {
        String ws = StoreState.normalizeWorkspace(workspace);
        synchronized (ring) {
            List<AuditEntry> matched = ring.values().stream()
                    .filter(e -> ws.equals(e.workspace()))
                    .filter(e -> featureSet == null || featureSet.equals(e.featureSet()))
                    .filter(e -> entity == null || entity.equals(e.entity()))
                    .toList();
            int n = Math.min(Math.max(limit, 0), matched.size());
            return matched.stream().skip(matched.size() - n).toList();
        }
    }
}
