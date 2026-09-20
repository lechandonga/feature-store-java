package com.github.lechandonga.featurestorejava.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.github.lechandonga.featurestorejava.domain.FeatureDefinition;
import com.github.lechandonga.featurestorejava.domain.FeatureRecord;
import com.github.lechandonga.featurestorejava.domain.RecordOrigin;
import com.github.lechandonga.featurestorejava.domain.RejectReason;
import com.github.lechandonga.featurestorejava.domain.WriteViolation;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.springframework.stereotype.Service;

/**
 * Validates live write batches and commits them with all-or-nothing semantics.
 *
 * <p>Every row in the batch is validated before anything is committed. Any violation rejects
 * the whole batch (zero rows take effect). The validation+commit section runs under the
 * {@link WriteCoordinator} lock so duplicate/out-of-order decisions cannot race.
 */
@Service
public class IngestService {

    private final FeatureRegistry registry;
    private final FeatureStorage storage;
    private final WriteCoordinator coordinator;
    private final DecisionLogger logger;

    public IngestService(FeatureRegistry registry, FeatureStorage storage,
                         WriteCoordinator coordinator, DecisionLogger logger) {
        this.registry = registry;
        this.storage = storage;
        this.coordinator = coordinator;
        this.logger = logger;
    }

    public WriteResult write(String batchId, List<RowInput> rows) {
        if (rows == null || rows.isEmpty()) {
            return WriteResult.rejected(batchId, RecordOrigin.INGEST,
                    List.of(new WriteViolation(-1, null, null, RejectReason.INVALID_BATCH,
                            "batch must contain at least one row")));
        }
        List<WriteViolation> violations = new ArrayList<>();
        List<FeatureRecord> prepared = new ArrayList<>();
        long now = System.currentTimeMillis();

        // Validation AND commit happen inside one critical section: another writer cannot land
        // between the duplicate/out-of-order decision and the snapshot swap.
        coordinator.withWriteLock(() -> {
            // Per (feature, version, entity) running high-water mark incl. rows in this batch.
            Map<String, Long> highWater = new HashMap<>();
            Set<String> batchKeys = new HashSet<>();

            for (int i = 0; i < rows.size(); i++) {
                RowInput row = rows.get(i);
                String feature = row.featureName();
                String entity = row.entityId();
                Long eventTs = row.eventTsMs();

                FeatureDefinition def = feature == null ? null
                        : (row.version() == null ? registry.getLatest(feature)
                                : registry.get(feature, row.version()));
                if (def == null) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            row.version() == null && feature != null
                                    ? RejectReason.UNKNOWN_FEATURE : RejectReason.VERSION_NOT_FOUND,
                            "feature=" + feature + " version=" + row.version() + " not registered"));
                    continue;
                }
                if (entity == null || entity.isBlank()) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            RejectReason.MISSING_ENTITY_ID, "feature=" + feature));
                    continue;
                }
                if (eventTs == null) {
                    violations.add(new WriteViolation(i, entity, null,
                            RejectReason.MISSING_EVENT_TIME, "feature=" + feature + " entity=" + entity));
                    continue;
                }
                if (eventTs <= 0) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            RejectReason.INVALID_TIME, "eventTs must be positive epoch millis"));
                    continue;
                }
                if (eventTs > now) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            RejectReason.FUTURE_EVENT_TIME,
                            "eventTs " + eventTs + " is later than write time " + now));
                    continue;
                }
                if (row.value() == null) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            RejectReason.MISSING_VALUE, "feature=" + feature));
                    continue;
                }
                if (!ValueTypeSupport.matches(row.value(), def.valueType())) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            RejectReason.TYPE_MISMATCH,
                            "value " + row.value() + " (" + row.value().getClass().getSimpleName()
                                    + ") not assignable to " + def.valueType()));
                    continue;
                }

                String hwKey = def.name() + "#" + def.version() + "#" + entity;
                long existingHw = storage.latestIngestedEventTs(def.name(), def.version(), entity) == null
                        ? Long.MIN_VALUE
                        : storage.latestIngestedEventTs(def.name(), def.version(), entity);
                long watermark = Math.max(existingHw, highWater.getOrDefault(hwKey, Long.MIN_VALUE));
                if (eventTs < watermark) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            RejectReason.OUT_OF_ORDER,
                            "eventTs " + eventTs + " precedes high-water mark " + watermark
                                    + " for live ingest; use the backfill channel for late data"));
                    continue;
                }

                String pointKey = hwKey + "#" + eventTs;
                boolean alreadyStored = storage.revisions(def.name(), def.version(), entity, eventTs).stream()
                        .anyMatch(r -> r.origin() == RecordOrigin.INGEST);
                if (alreadyStored || !batchKeys.add(pointKey)) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            RejectReason.DUPLICATE_WRITE,
                            "an INGEST value for (" + def.name() + " v" + def.version()
                                    + ", entity=" + entity + ", eventTs=" + eventTs + ") already exists"));
                    continue;
                }

                highWater.put(hwKey, Math.max(highWater.getOrDefault(hwKey, Long.MIN_VALUE), eventTs));
                Object normalized = ValueTypeSupport.normalize(row.value(), def.valueType());
                long ingestTs = row.ingestTsMs() != null ? row.ingestTsMs() : now;
                prepared.add(new FeatureRecord(def.name(), def.version(), entity, eventTs, normalized,
                        ingestTs, ingestTs, RecordOrigin.INGEST));
            }

            if (violations.isEmpty()) {
                storage.commitBatch(prepared);
            }
        });

        if (!violations.isEmpty()) {
            logger.log("write", "*", null, "*", null, null, "REJECTED",
                    "batchId=" + batchId + " rejected as a whole with " + violations.size()
                            + " violation(s); no row committed: " + violations);
            return WriteResult.rejected(batchId, RecordOrigin.INGEST, violations);
        }

        logger.log("write", "*", null, "*", null, null, "COMMITTED",
                "batchId=" + batchId + " committed " + prepared.size() + " rows atomically at " + now);
        return new WriteResult(batchId, true, RecordOrigin.INGEST, prepared.size(), now, List.of());
    }
}
