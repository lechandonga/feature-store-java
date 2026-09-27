package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.error.CapacityExceededException;
import com.github.lechandonga.featurestorejava.error.NotFoundException;
import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.RejectionReason;
import com.github.lechandonga.featurestorejava.model.RowRejection;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteResult;
import com.github.lechandonga.featurestorejava.model.WriteRow;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Atomic, schema-validated feature ingestion within one workspace.
 *
 * <p>Validation happens <em>before</em> any state change, against a consistent
 * write-locked snapshot. A batch is rejected as a whole — leaving zero partial
 * data — when any row:
 * <ul>
 *   <li>misses the entity key or a required field;</li>
 *   <li>carries an unknown field, or a value of the wrong type;</li>
 *   <li>appears twice within the batch (same entity + event time);</li>
 *   <li>is out of order for its entity within the batch;</li>
 *   <li>duplicates an already stored (entity, event time). An identical value
 *       is a idempotent collision ({@code DUPLICATE_ALREADY_STORED}); a different
 *       value at the same event time must go through backfill
 *       ({@code SAME_TIME_DIFFERENT_VALUE}).</li>
 * </ul>
 *
 * <p>Late-arriving data (an event time older than stored points) is accepted:
 * as-of filtering keeps it invisible to past time points, so it can never leak.
 *
 * <p>The workspace historical-points capacity limit is checked inside the same
 * write-lock critical section, <em>after</em> validation and <em>before</em>
 * any append: when {@code currentPoints + batchRows > maxPoints} the whole
 * batch is rejected and nothing is partially written. A missing workspace is a
 * distinguishable {@code FEATURE_SET_NOT_FOUND}-style precondition failure.
 */
public class WriteService {

    private final WorkspaceManager workspaces;
    private final FeatureRegistry registryService;
    private final TimeSource time;

    public WriteService(WorkspaceManager workspaces, FeatureRegistry registry, TimeSource time) {
        this.workspaces = workspaces;
        this.registryService = registry;
        this.time = time;
    }

    /** Backward-compatible constructor: operates against the default workspace. */
    public WriteService(StoreState state, FeatureRegistry registry, AuditLog audit, TimeSource time) {
        this(WorkspaceManager.wrappingDefault(state, time), registry, time);
    }

    public WriteResult submit(WriteBatch batch) {
        return submit(WorkspaceManager.DEFAULT_WORKSPACE, batch);
    }

    public WriteResult submit(String workspace, WriteBatch batch) {
        String ws = WorkspaceManager.resolve(workspace);
        StoreState state = workspaces.requireState(ws);
        if (batch == null) {
            throw new BatchRejectedException(ws, null, null,
                    List.of(reject(-1, null, null, RejectionReason.EMPTY_BATCH,
                            "request body is required", Map.of())));
        }
        // resolve the definition up front (distinct failure mode from row violations)
        FeatureSetDef def;
        try {
            def = registryService.requireVersion(ws, batch.featureSet(),
                    batch.version() == 0 ? null : batch.version());
        } catch (NotFoundException missing) {
            // distinguish "no such feature set" from "exists but wrong version"
            FeatureSetDef existing = state.registry().getLatest(batch.featureSet());
            boolean setExists = existing != null;
            RejectionReason reason = setExists
                    ? RejectionReason.VERSION_MISMATCH
                    : RejectionReason.FEATURE_SET_NOT_FOUND;
            String detail = setExists
                    ? "feature set '" + batch.featureSet() + "' latest version is "
                            + existing.version() + " in workspace '" + ws
                            + "', batch targets " + batch.version()
                    : missing.getMessage();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("workspace", ws);
            if (setExists) {
                evidence.put("latestVersion", existing.version());
            }
            throw new BatchRejectedException(ws, batch.featureSet(), batch.version(),
                    List.of(reject(-1, null, null, reason, detail, evidence)));
        }

        List<RowRejection> rejections = new ArrayList<>();
        List<StoredPoint> accepted;
        boolean committed = false;

        // Validation and commit run inside ONE write-lock critical section.
        // Splitting them (read-lock validate, then write-lock commit) would open
        // a TOCTOU window where a concurrent writer commits the same
        // (entity, eventTime) between check and insert, or consumes capacity.
        state.lock().writeLock().lock();
        try {
            if (batch.rows() == null || batch.rows().isEmpty()) {
                throw rejected(state, ws, def, List.of(reject(-1, null, null,
                        RejectionReason.EMPTY_BATCH, "a batch must contain at least one row",
                        Map.of())));
            }
            accepted = validate(state, def, batch.rows(), rejections);
            if (!rejections.isEmpty()) {
                throw rejected(state, ws, def, rejections);
            }
            // capacity: projected point count must fit, or the ENTIRE batch fails
            checkPointsCapacity(state, ws, def, accepted.size(),
                    firstEntity(accepted), accepted.get(0).eventTime());

            // atomic commit: all offline appends and the online chain swap
            long ingestTime = time.now();
            List<StoredPoint> points = new ArrayList<>(accepted.size());
            List<String> ids = new ArrayList<>(accepted.size());
            for (StoredPoint p : accepted) {
                StoredPoint stored = new StoredPoint(
                        UUID.randomUUID().toString(), p.featureSet(), p.version(), p.entity(),
                        p.eventTime(), ingestTime, p.values(), null, "WRITE", null);
                state.offline().append(stored);
                points.add(stored);
                ids.add(stored.id());
            }
            state.online().applyBatch(def.name(), def.version(), points);
            state.audit().record(ws, "WRITE", def.name(), def.version(), null, null, "ACCEPTED",
                    "batch committed atomically",
                    Map.of("workspace", ws, "rows", ids.size(), "ingestTime", ingestTime,
                            "pointIds", ids));
            committed = true;
            return new WriteResult(points.size(), List.copyOf(ids));
        } finally {
            state.lock().writeLock().unlock();
            if (committed) {
                state.notifyCommit();
            }
        }
    }

    private static String firstEntity(List<StoredPoint> points) {
        return points.isEmpty() ? null : points.get(0).entity();
    }

    private void checkPointsCapacity(StoreState state, String ws, FeatureSetDef def,
                                     int requested, String entity, Long eventTime) {
        com.github.lechandonga.featurestorejava.model.WorkspaceLimits limits =
                workspaces.require(ws).info().limits();
        if (limits.maxPoints() == null) {
            return;
        }
        long limit = limits.maxPoints();
        long current = state.offline().totalPoints();
        if (current + requested > limit) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("workspace", ws);
            evidence.put("featureSet", def.name());
            evidence.put("version", def.version());
            evidence.put("entity", entity);
            evidence.put("eventTime", eventTime);
            evidence.put("batchRows", requested);
            state.audit().record(ws, "WRITE", def.name(), def.version(), entity, eventTime,
                    "REJECTED", "workspace historical-points capacity exceeded; batch rejected "
                            + "as a whole, state unchanged", evidence);
            throw new CapacityExceededException(ws, def.name(), def.version(), "POINTS",
                    limit, current, requested, evidence);
        }
    }

    // ---- validation -----------------------------------------------------

    private List<StoredPoint> validate(StoreState state, FeatureSetDef def, List<WriteRow> rows,
                                       List<RowRejection> rejections) {
        Map<String, FeatureDef> declared = new LinkedHashMap<>();
        for (FeatureDef f : def.features()) {
            declared.put(f.name(), f);
        }
        // existing visible points by entity/eventTime, for duplicate detection
        Map<String, StoredPoint> existing = new LinkedHashMap<>();
        for (String entity : state.offline().entities(def.name(), def.version())) {
            for (StoredPoint p : state.online().chain(def.name(), def.version(), entity)) {
                existing.put(entity + "|" + p.eventTime(), p);
            }
        }

        // per-entity latest event time seen while scanning, for ordering checks
        Map<String, Long> lastEventInBatch = new LinkedHashMap<>();
        // keys already seen within this batch, for in-batch duplicate checks
        Map<String, Integer> seenInBatch = new LinkedHashMap<>();

        List<StoredPoint> accepted = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            WriteRow row = rows.get(i);
            List<RowRejection> rowViolations = new ArrayList<>();
            if (row == null || row.fields() == null) {
                rowViolations.add(reject(i, null, row == null ? null : row.eventTime(),
                        RejectionReason.EMPTY_BATCH, "row and its fields must be non-null",
                        Map.of()));
                rejections.addAll(rowViolations);
                continue;
            }
            Object rawEntity = row.fields().get(def.entityKey());
            String entity = rawEntity == null ? null : String.valueOf(rawEntity);
            if (rawEntity == null) {
                rowViolations.add(reject(i, null, row.eventTime(),
                        RejectionReason.ROW_MISSING_ENTITY_KEY,
                        "entity key field '" + def.entityKey() + "' is required",
                        Map.of("entityKey", def.entityKey())));
            }

            // 1) unknown fields
            for (String field : row.fields().keySet()) {
                if (def.entityKey().equals(field)) {
                    continue;
                }
                if (!declared.containsKey(field)) {
                    rowViolations.add(reject(i, entity, row.eventTime(),
                            RejectionReason.UNKNOWN_FIELD,
                            "field '" + field + "' is not declared in v" + def.version(),
                            Map.of("field", field)));
                }
            }
            // 2) required presence + 3) type match; build normalized value map
            Map<String, Object> values = new TreeMap<>();
            for (FeatureDef f : def.features()) {
                Object v = row.fields().get(f.name());
                if (v == null) {
                    if (f.required()) {
                        rowViolations.add(reject(i, entity, row.eventTime(),
                                RejectionReason.MISSING_REQUIRED_FIELD,
                                "required feature '" + f.name() + "' is absent",
                                Map.of("feature", f.name())));
                    }
                    continue;
                }
                if (!f.type().matches(v)) {
                    rowViolations.add(reject(i, entity, row.eventTime(),
                            RejectionReason.TYPE_MISMATCH,
                            "feature '" + f.name() + "' expects " + f.type()
                                    + " but received " + v.getClass().getSimpleName()
                                    + " (" + v + ")",
                            Map.of("feature", f.name(), "expected", f.type().name(),
                                    "actual", v.getClass().getSimpleName())));
                } else {
                    values.put(f.name(), f.type().normalize(v));
                }
            }

            if (entity != null) {
                String key = entity + "|" + row.eventTime();
                // 4) duplicate within batch
                Integer firstIndex = seenInBatch.put(key, i);
                if (firstIndex != null) {
                    rowViolations.add(reject(i, entity, row.eventTime(),
                            RejectionReason.DUPLICATE_WITHIN_BATCH,
                            "same entity/eventTime already appears at row " + firstIndex,
                            Map.of("firstRow", firstIndex)));
                }
                // 5) reverse order within batch for the same entity
                Long last = lastEventInBatch.get(entity);
                if (last != null && row.eventTime() < last) {
                    rowViolations.add(reject(i, entity, row.eventTime(),
                            RejectionReason.REVERSE_ORDER_WITHIN_BATCH,
                            "eventTime " + row.eventTime() + " precedes earlier row at "
                                    + last + " for the same entity in this batch",
                            Map.of("previousEventTime", last)));
                }
                lastEventInBatch.put(entity, row.eventTime());

                // 6) collision with already stored data
                StoredPoint prior = existing.get(key);
                if (prior != null) {
                    if (prior.values().equals(values)) {
                        rowViolations.add(reject(i, entity, row.eventTime(),
                                RejectionReason.DUPLICATE_ALREADY_STORED,
                                "identical value already stored as point " + prior.id()
                                        + "; the write is rejected, state is unchanged",
                                Map.of("existingPointId", prior.id())));
                    } else {
                        rowViolations.add(reject(i, entity, row.eventTime(),
                                RejectionReason.SAME_TIME_DIFFERENT_VALUE,
                                "a different value already exists at this event time (point "
                                        + prior.id() + "); use /api/backfill to correct it",
                                Map.of("existingPointId", prior.id(),
                                        "existingValues", prior.values(),
                                        "attemptedValues", values)));
                    }
                }
            }

            if (rowViolations.isEmpty()) {
                accepted.add(new StoredPoint(null, def.name(), def.version(), entity,
                        row.eventTime(), -1L, values, null, "WRITE", null));
            } else {
                rejections.addAll(rowViolations);
            }
        }
        return accepted;
    }

    private BatchRejectedException rejected(StoreState state, String ws, FeatureSetDef def,
                                            List<RowRejection> rejections) {
        // one explainable entry per offending row: carries entity, time point,
        // distinguishable reason and the row's position
        for (RowRejection r : rejections) {
            Map<String, Object> evidence = new LinkedHashMap<>(r.evidence());
            evidence.put("rowIndex", r.rowIndex());
            state.audit().record(ws, "WRITE_ROW", def.name(), def.version(), r.entity(),
                    r.eventTime(), "REJECTED", r.reason().name() + ": " + r.detail(), evidence);
        }
        state.audit().record(ws, "WRITE", def.name(), def.version(), null, null, "REJECTED",
                "batch validation failed; no row applied (" + rejections.size()
                        + " violation(s))",
                Map.of("workspace", ws, "violations", rejections.size()));
        return new BatchRejectedException(ws, def.name(), def.version(), rejections);
    }

    private static RowRejection reject(int rowIndex, String entity, Long eventTime,
                                       RejectionReason reason, String detail,
                                       Map<String, Object> evidence) {
        return new RowRejection(rowIndex, entity, eventTime, reason, detail,
                Map.copyOf(evidence));
    }
}
