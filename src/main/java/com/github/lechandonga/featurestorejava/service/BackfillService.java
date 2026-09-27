package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.error.NotFoundException;
import com.github.lechandonga.featurestorejava.error.WorkspaceCapacityExceededException;
import com.github.lechandonga.featurestorejava.model.Correction;
import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.RejectionReason;
import com.github.lechandonga.featurestorejava.model.RowRejection;
import com.github.lechandonga.featurestorejava.model.StoredPoint;
import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.BackfillResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Backfill / recompute.
 *
 * <p>A correction never mutates history. For each target point a brand-new
 * {@link StoredPoint} is appended with:
 * <ul>
 *   <li>the same entity and event time (business time is immutable — a
 *       mismatch rejects the whole batch with {@code EVENT_TIME_IMMUTABLE});</li>
 *   <li>the current (recomputed) {@code ingestTime};</li>
 *   <li>{@code supersedes = targetPointId}, which hides the old value from
 *       present-time reads while leaving every past as-of view unchanged and
 *       reproducible.</li>
 * </ul>
 *
 * <p>Online stability during a recompute: all corrections are validated first
 * against a write-locked snapshot, then the affected online chains are
 * rebuilt and switched in one critical section. A concurrent online reader
 * therefore observes either the pre-recompute or post-recompute chain, never
 * a partially recomputed entity set.
 *
 * <p>A {@code correlationId} makes a recompute job idempotent: replaying an
 * already applied correlation returns the previous result instead of appending
 * a second correction layer. Idempotency records live in the per-workspace
 * state and are persisted with the snapshot, so they survive restarts.
 */
public class BackfillService {

    private final StoreState state;
    private final FeatureRegistry registry;
    private final AuditLog audit;
    private final TimeSource time;

    public BackfillService(StoreState state, FeatureRegistry registry, AuditLog audit,
                           TimeSource time) {
        this.state = state;
        this.registry = registry;
        this.audit = audit;
        this.time = time;
    }

    /** Backfill within the default workspace (pre-workspace call sites). */
    public BackfillResult backfill(BackfillRequest request) {
        return backfill(StoreState.DEFAULT_WORKSPACE, request);
    }

    public BackfillResult backfill(String workspace, BackfillRequest request) {
        String ws = StoreState.normalizeWorkspace(workspace);
        if (request == null) {
            throw new BatchRejectedException(null, null,
                    List.of(reject(-1, null, null, RejectionReason.EMPTY_BACKFILL,
                            "request body is required", Map.of())));
        }
        FeatureSetDef def;
        try {
            def = registry.requireVersion(ws, request.featureSet(),
                    request.version() == 0 ? null : request.version());
        } catch (NotFoundException e) {
            throw new BatchRejectedException(request.featureSet(), request.version(),
                    List.of(reject(-1, null, null, RejectionReason.VERSION_MISMATCH,
                            e.getMessage(), Map.of())));
        }

        boolean committed = false;
        state.lock().writeLock().lock();
        try {
            Map<String, List<String>> appliedByCorrelation = state.correlations(ws);
            // idempotent replay of a completed recompute job
            if (request.correlationId() != null
                    && appliedByCorrelation.containsKey(request.correlationId())) {
                List<String> ids = appliedByCorrelation.get(request.correlationId());
                audit.record(ws, "BACKFILL", def.name(), def.version(), null, null, "ACCEPTED",
                        "idempotent replay of correlationId " + request.correlationId(),
                        Map.of("pointIds", ids, "idempotent", true));
                return new BackfillResult(ids.size(), List.copyOf(ids));
            }

            List<RowRejection> rejections = new ArrayList<>();
            if (request.corrections() == null || request.corrections().isEmpty()) {
                throw rejected(ws, def, List.of(reject(-1, null, null,
                        RejectionReason.EMPTY_BACKFILL,
                        "a backfill must contain at least one correction", Map.of())));
            }
            List<StoredPoint> validated = validate(ws, def, request.corrections(), rejections);
            if (!rejections.isEmpty()) {
                throw rejected(ws, def, rejections);
            }

            // whole-batch capacity check: on overflow no correction is appended
            try {
                state.ensurePointCapacity(ws, def.name(), validated.size());
            } catch (WorkspaceCapacityExceededException cap) {
                audit.record(ws, "BACKFILL", def.name(), def.version(), null, null, "REJECTED",
                        "workspace capacity exceeded on " + cap.getDimension()
                                + "; backfill rejected atomically",
                        Map.of("dimension", cap.getDimension(), "limit", cap.getLimit(),
                                "current", cap.getCurrent(), "requested", cap.getRequested()));
                throw cap;
            }

            long ingestTime = time.now();
            List<StoredPoint> appended = new ArrayList<>(validated.size());
            List<String> ids = new ArrayList<>(validated.size());
            for (StoredPoint v : validated) {
                StoredPoint p = new StoredPoint(UUID.randomUUID().toString(), def.name(),
                        def.version(), v.entity(), v.eventTime(), ingestTime, v.values(),
                        v.supersedes(), "BACKFILL", v.reason());
                state.offline(ws).append(p);
                appended.add(p);
                ids.add(p.id());
            }
            // one atomic online chain switch for the entire recompute batch
            state.online(ws).applyBatch(def.name(), def.version(), appended);
            if (request.correlationId() != null) {
                appliedByCorrelation.put(request.correlationId(), List.copyOf(ids));
            }
            audit.record(ws, "BACKFILL", def.name(), def.version(), null, null, "ACCEPTED",
                    "recompute committed atomically; past as-of views unchanged",
                    Map.of("corrections", ids.size(), "ingestTime", ingestTime,
                            "pointIds", ids,
                            "correlationId", String.valueOf(request.correlationId())));
            committed = true;
            return new BackfillResult(appended.size(), List.copyOf(ids));
        } finally {
            state.lock().writeLock().unlock();
            if (committed) {
                state.notifyCommit();
            }
        }
    }

    private List<StoredPoint> validate(String ws, FeatureSetDef def,
                                       List<Correction> corrections,
                                       List<RowRejection> rejections) {
        Map<String, FeatureDef> declared = new LinkedHashMap<>();
        for (FeatureDef f : def.features()) {
            declared.put(f.name(), f);
        }
        Set<String> targetsSeen = new LinkedHashSet<>();
        List<StoredPoint> validated = new ArrayList<>();

        for (int i = 0; i < corrections.size(); i++) {
            Correction c = corrections.get(i);
            List<RowRejection> row = new ArrayList<>();
            if (c == null || c.targetPointId() == null) {
                row.add(reject(i, null, null, RejectionReason.TARGET_POINT_NOT_FOUND,
                        "targetPointId is required", Map.of()));
                rejections.addAll(row);
                continue;
            }
            StoredPoint target = state.offline(ws).getPoint(c.targetPointId());
            if (target == null) {
                // distinguish "no such point at all" from "point lives in
                // another workspace" — the latter is a cross-workspace access
                String owner = state.workspaceOfPoint(c.targetPointId());
                if (owner != null) {
                    row.add(reject(i, c.entity(), c.eventTime(),
                            RejectionReason.CROSS_WORKSPACE_ACCESS,
                            "point " + c.targetPointId() + " belongs to workspace '" + owner
                                    + "' and cannot be corrected from workspace '" + ws + "'",
                            Map.of("targetPointId", c.targetPointId(),
                                    "ownerWorkspace", owner,
                                    "requestedWorkspace", ws)));
                } else {
                    row.add(reject(i, c.entity(), c.eventTime(),
                            RejectionReason.TARGET_POINT_NOT_FOUND,
                            "no stored point with id " + c.targetPointId()
                                    + " in workspace '" + ws + "'",
                            Map.of("targetPointId", c.targetPointId())));
                }
            } else {
                if (!target.featureSet().equals(def.name()) || target.version() != def.version()) {
                    row.add(reject(i, target.entity(), target.eventTime(),
                            RejectionReason.TARGET_POINT_MISMATCH,
                            "point belongs to a different feature set/version",
                            Map.of("targetPointId", target.id(),
                                    "actualFeatureSet", target.featureSet(),
                                    "actualVersion", target.version())));
                }
                if (c.entity() != null && !c.entity().equals(target.entity())) {
                    row.add(reject(i, c.entity(), c.eventTime(),
                            RejectionReason.TARGET_POINT_MISMATCH,
                            "entity '" + c.entity() + "' does not match stored entity '"
                                    + target.entity() + "'",
                            Map.of("targetPointId", target.id())));
                }
                if (c.eventTime() != target.eventTime()) {
                    row.add(reject(i, c.entity(), c.eventTime(),
                            RejectionReason.EVENT_TIME_IMMUTABLE,
                            "backfill must not rewrite event time: stored="
                                    + target.eventTime() + ", requested=" + c.eventTime(),
                            Map.of("storedEventTime", target.eventTime(),
                                    "requestedEventTime", c.eventTime())));
                }
                if (!targetsSeen.add(target.id())) {
                    row.add(reject(i, target.entity(), target.eventTime(),
                            RejectionReason.DUPLICATE_WITHIN_BATCH,
                            "target point " + target.id() + " is corrected twice in one batch",
                            Map.of("targetPointId", target.id())));
                }
                // a point already produced by a backfill cannot be re-corrected in a new job:
                // correct the original point instead, keeping the correction chain linear
                if (target.supersedes() != null) {
                    row.add(reject(i, target.entity(), target.eventTime(),
                            RejectionReason.TARGET_POINT_MISMATCH,
                            "point " + target.id() + " is itself a correction of "
                                    + target.supersedes()
                                    + "; correct the original point to keep history linear",
                            Map.of("originalPointId", target.supersedes())));
                }
            }

            // schema-validate the corrected value map exactly like a write
            Map<String, Object> values = new TreeMap<>();
            if (c.correctedValues() == null) {
                row.add(reject(i, c.entity(), c.eventTime(), RejectionReason.EMPTY_BACKFILL,
                        "correctedValues must be non-null", Map.of()));
            } else {
                for (String field : c.correctedValues().keySet()) {
                    if (!declared.containsKey(field)) {
                        row.add(reject(i, c.entity(), c.eventTime(), RejectionReason.UNKNOWN_FIELD,
                                "field '" + field + "' is not declared in v" + def.version(),
                                Map.of("field", field)));
                    }
                }
                for (FeatureDef f : def.features()) {
                    Object v = c.correctedValues().get(f.name());
                    if (v == null) {
                        if (f.required()) {
                            row.add(reject(i, c.entity(), c.eventTime(),
                                    RejectionReason.MISSING_REQUIRED_FIELD,
                                    "required feature '" + f.name() + "' is absent",
                                    Map.of("feature", f.name())));
                        }
                        continue;
                    }
                    if (!f.type().matches(v)) {
                        row.add(reject(i, c.entity(), c.eventTime(),
                                RejectionReason.TYPE_MISMATCH,
                                "feature '" + f.name() + "' expects " + f.type()
                                        + " but received " + v.getClass().getSimpleName(),
                                Map.of("feature", f.name(), "expected", f.type().name())));
                    } else {
                        values.put(f.name(), f.type().normalize(v));
                    }
                }
            }

            if (row.isEmpty() && target != null) {
                validated.add(new StoredPoint(null, def.name(), def.version(),
                        target.entity(), target.eventTime(), -1L, values, target.id(),
                        "BACKFILL", c.reason()));
            } else {
                rejections.addAll(row);
            }
        }
        return validated;
    }

    private BatchRejectedException rejected(String ws, FeatureSetDef def,
                                            List<RowRejection> rejections) {
        for (RowRejection r : rejections) {
            Map<String, Object> evidence = new LinkedHashMap<>(r.evidence());
            evidence.put("rowIndex", r.rowIndex());
            audit.record(ws, "BACKFILL_ROW", def.name(), def.version(), r.entity(), r.eventTime(),
                    "REJECTED", r.reason().name() + ": " + r.detail(), evidence);
        }
        audit.record(ws, "BACKFILL", def.name(), def.version(), null, null, "REJECTED",
                "backfill validation failed; state unchanged (" + rejections.size()
                        + " violation(s))",
                Map.of("violations", rejections.size()));
        return new BatchRejectedException(def.name(), def.version(), rejections);
    }

    private static RowRejection reject(int rowIndex, String entity, Long eventTime,
                                       RejectionReason reason, String detail,
                                       Map<String, Object> evidence) {
        return new RowRejection(rowIndex, entity, eventTime, reason, detail, Map.copyOf(evidence));
    }
}
