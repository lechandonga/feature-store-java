package com.github.lechandonga.featurestorejava.service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory structured audit log of every validation, commit and serving decision.
 * Each entry always carries entity, time point(s) and the human-readable decision basis,
 * and is also emitted via SLF4J so rejections and point-in-time decisions are reproducible.
 */
@Component
public class DecisionLogger {

    private static final Logger log = LoggerFactory.getLogger(DecisionLogger.class);
    private static final int MAX_ENTRIES = 5000;

    private final LinkedList<DecisionLogEntry> entries = new LinkedList<>();

    public void log(String action, String featureName, Integer version, String entityId,
                    Long eventTsMs, Long asOfMs, String decision, String basis) {
        DecisionLogEntry entry = new DecisionLogEntry(System.currentTimeMillis(), action,
                featureName, version, entityId, eventTsMs, asOfMs, decision, basis);
        log.info("feature-store decision | action={} feature={} version={} entity={} eventTs={} asOf={} decision={} basis={}",
                action, featureName, version, entityId, eventTsMs, asOfMs, decision, basis);
        synchronized (entries) {
            entries.addFirst(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.removeLast();
            }
        }
    }

    public List<DecisionLogEntry> recent(int limit) {
        synchronized (entries) {
            int n = Math.min(limit, entries.size());
            return new ArrayList<>(entries.subList(0, n));
        }
    }
}
