package com.github.lechandonga.featurestorejava.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * Backfill / recompute channel for corrected historical values.
 *
 * <p>A correction never mutates or deletes an existing record; it is appended as a new revision
 * ({@link RecordOrigin#BACKFILL} or {@link RecordOrigin#RECOMPUTE}) whose {@code ingestTs} is the
 * event time and whose {@code revisionTs} is the commit wall-clock time. Therefore:
 * <ul>
 *   <li>point-in-time reads with asOf before the correction ignore it ({@code revisionTs > asOf}),
 *       so backfill cannot leak into historical training samples and backtests stay reproducible;</li>
 *   <li>online reads and offline reads at asOf &ge; revisionTs see the corrected value, and its
 *       provenance (origin / revisionTs / old revision) is fully explainable;</li>
 *   <li>the batch still commits as one atomic snapshot swap, so concurrent online reads never see
 *       a half-recomputed feature.</li>
 * </ul>
 * Unlike live ingest, out-of-order event times are allowed here (that is what backfill is for).
 */
@Service
public class BackfillService {

    private final FeatureRegistry registry;
    private final FeatureStorage storage;
    private final WriteCoordinator coordinator;
    private final DecisionLogger logger;

    public BackfillService(FeatureRegistry registry, FeatureStorage storage,
                           WriteCoordinator coordinator, DecisionLogger logger) {
        this.registry = registry;
        this.storage = storage;
        this.coordinator = coordinator;
        this.logger = logger;
    }

    public WriteResult backfill(String batchId, List<RowInput> rows) {
        return apply(batchId, rows, RecordOrigin.BACKFILL);
    }

    public WriteResult recompute(String batchId, List<RowInput> rows) {
        return apply(batchId, rows, RecordOrigin.RECOMPUTE);
    }

    private WriteResult apply(String batchId, List<RowInput> rows, RecordOrigin origin) {
        if (rows == null || rows.isEmpty()) {
            return WriteResult.rejected(batchId, origin,
                    List.of(new WriteViolation(-1, null, null, RejectReason.INVALID_BATCH,
                            "batch must contain at least one row")));
        }
        List<WriteViolation> violations = new ArrayList<>();
        List<FeatureRecord> prepared = new ArrayList<>();
        long now = System.currentTimeMillis();

        coordinator.withWriteLock(() -> {
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
                if (eventTs == null || eventTs <= 0) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            eventTs == null ? RejectReason.MISSING_EVENT_TIME : RejectReason.INVALID_TIME,
                            "feature=" + feature));
                    continue;
                }
                if (eventTs > now) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            RejectReason.FUTURE_EVENT_TIME,
                            "eventTs " + eventTs + " is later than commit time " + now));
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
                            "value " + row.value() + " not assignable to " + def.valueType()));
                    continue;
                }
                String pointKey = def.name() + "#" + def.version() + "#" + entity + "#" + eventTs + "#" + origin;
                boolean alreadyStored = storage.revisions(def.name(), def.version(), entity, eventTs).stream()
                        .anyMatch(r -> r.origin() == origin);
                if (alreadyStored || !batchKeys.add(pointKey)) {
                    violations.add(new WriteViolation(i, entity, eventTs,
                            RejectReason.DUPLICATE_WRITE,
                            "a " + origin + " revision for (" + def.name() + " v" + def.version()
                                    + ", entity=" + entity + ", eventTs=" + eventTs + ") already exists"));
                    continue;
                }
                Object normalized = ValueTypeSupport.normalize(row.value(), def.valueType());
                // ingestTs = eventTs (the corrected historical value is dated back); revisionTs = now.
                prepared.add(new FeatureRecord(def.name(), def.version(), entity, eventTs, normalized,
                        eventTs, now, origin));
            }
            if (violations.isEmpty()) {
                storage.commitBatch(prepared);
            }
        });

        if (!violations.isEmpty()) {
            logger.log(origin.name().toLowerCase(), "*", null, "*", null, null, "REJECTED",
                    "batchId=" + batchId + " rejected as a whole with " + violations.size()
                            + " violation(s); no correction committed: " + violations);
            return WriteResult.rejected(batchId, origin, violations);
        }
        logger.log(origin.name().toLowerCase(), "*", null, "*", null, null, "COMMITTED",
                "batchId=" + batchId + " committed " + prepared.size() + " " + origin
                        + " revision(s) atomically at revisionTs=" + now
                        + "; pre-revision asOf reads remain unchanged");
        return new WriteResult(batchId, true, origin, prepared.size(), now, List.of());
    }
}
