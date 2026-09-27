package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.error.CapacityExceededException;
import com.github.lechandonga.featurestorejava.error.ConflictDetail;
import com.github.lechandonga.featurestorejava.error.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.error.NotFoundException;
import com.github.lechandonga.featurestorejava.model.ConflictReason;
import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.WorkspaceLimits;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Feature registration and backward-compatible version evolution, scoped to a
 * caller-selected workspace.
 *
 * <p>Versioning rules (enforced at registration, never silently altered):
 * <ul>
 *   <li>version numbers are positive and strictly monotonic — evolution always
 *       creates exactly {@code latest + 1};</li>
 *   <li>the entity key is immutable across versions;</li>
 *   <li>features may only be <em>added</em> (at the end of the ordered list).
 *       Removing, renaming, reordering or changing the type / required flag /
 *       default value / missing policy of an existing feature is incompatible
 *       and rejected with distinguishable {@link ConflictReason}s;</li>
 *   <li>the freshness TTL may be introduced or lengthened, never shortened,
 *       because shortening would retroactively turn historical reads stale;</li>
 *   <li>all detected conflicts are reported together; nothing is partially
 *       registered.</li>
 * </ul>
 *
 * <p>Each workspace has its own registry: the same feature set name may be
 * registered with a completely different definition in another workspace. The
 * workspace feature-set-count capacity limit is enforced on initial
 * registration (evolution never adds a feature set, so it is not limited).
 * Calls without a workspace argument target the default workspace.
 */
public class FeatureRegistry {

    private final WorkspaceManager workspaces;
    private final TimeSource time;

    public FeatureRegistry(WorkspaceManager workspaces, TimeSource time) {
        this.workspaces = workspaces;
        this.time = time;
    }

    /** Test/backward-compatible constructor: defaults-only workspace access. */
    public FeatureRegistry(StoreState state, AuditLog audit, TimeSource time) {
        this(defaultManager(state, audit, time), time);
    }

    private static WorkspaceManager defaultManager(StoreState state, AuditLog audit, TimeSource time) {
        return WorkspaceManager.wrappingDefault(state, time);
    }

    public FeatureSetDef register(RegisterRequest request) {
        return register(WorkspaceManager.DEFAULT_WORKSPACE, request);
    }

    public FeatureSetDef register(String workspace, RegisterRequest request) {
        StoreState state = workspaces.requireState(workspace);
        String ws = WorkspaceManager.resolve(workspace);
        List<ConflictDetail> conflicts = new ArrayList<>();
        validateShape(request.name(), request.entityKey(), request.features(), conflicts);
        if (!conflicts.isEmpty()) {
            throw conflict(state, ws, request.name(), conflicts);
        }
        state.lock().writeLock().lock();
        try {
            if (state.registry().getLatest(request.name()) != null) {
                conflicts.add(new ConflictDetail(ConflictReason.ALREADY_EXISTS, request.name(),
                        "feature set already exists; use the versions endpoint to evolve it"));
                throw conflict(state, ws, request.name(), conflicts);
            }
            checkFeatureSetCapacity(state, ws, request.name());
            FeatureSetDef def = new FeatureSetDef(request.name(), 1, request.entityKey(),
                    request.ttlMillis(), List.copyOf(request.features()), time.now());
            state.registry().put(def);
            state.audit().record(ws, "REGISTER", request.name(), 1, null, null, "ACCEPTED",
                    "initial version registered",
                    Map.of("workspace", ws, "featureCount", def.features().size()));
            return def;
        } finally {
            state.lock().writeLock().unlock();
        }
    }

    public FeatureSetDef evolve(String name, List<FeatureDef> features, Long ttlMillis) {
        return evolve(WorkspaceManager.DEFAULT_WORKSPACE, name, features, ttlMillis);
    }

    public FeatureSetDef evolve(String workspace, String name, List<FeatureDef> features,
                                Long ttlMillis) {
        StoreState state = workspaces.requireState(workspace);
        String ws = WorkspaceManager.resolve(workspace);
        List<ConflictDetail> conflicts = new ArrayList<>();
        validateShape(name, "evolve", features, conflicts);
        state.lock().writeLock().lock();
        try {
            FeatureSetDef latest = state.registry().getLatest(name);
            if (latest == null) {
                conflicts.add(0, new ConflictDetail(ConflictReason.FEATURE_SET_NOT_FOUND, name,
                        "register the feature set before evolving it"));
                throw conflict(state, ws, name, conflicts);
            }
            compareVersions(latest, features, ttlMillis, conflicts);
            if (!conflicts.isEmpty()) {
                throw conflict(state, ws, name, conflicts);
            }
            int nextVersion = latest.version() + 1;
            FeatureSetDef def = new FeatureSetDef(name, nextVersion, latest.entityKey(),
                    ttlMillis != null ? ttlMillis : latest.ttlMillis(),
                    List.copyOf(features), time.now());
            state.registry().put(def);
            int added = features.size() - latest.features().size();
            state.audit().record(ws, "EVOLVE", name, nextVersion, null, null, "ACCEPTED",
                    "compatible version: " + added + " feature(s) added",
                    Map.of("workspace", ws, "previousVersion", latest.version(),
                            "addedFeatures", added));
            return def;
        } finally {
            state.lock().writeLock().unlock();
        }
    }

    public FeatureSetDef requireVersion(String name, Integer version) {
        return requireVersion(WorkspaceManager.DEFAULT_WORKSPACE, name, version);
    }

    public FeatureSetDef requireVersion(String workspace, String name, Integer version) {
        StoreState state = workspaces.requireState(workspace);
        FeatureSetDef def = version == null
                ? state.registry().getLatest(name)
                : state.registry().getVersion(name, version);
        if (def == null) {
            throw new NotFoundException("feature set '" + name + "' version "
                    + (version == null ? "<latest>" : version) + " not found in workspace '"
                    + WorkspaceManager.resolve(workspace) + "'");
        }
        return def;
    }

    /** Resolve the store state for callers that operate on a resolved version. */
    public StoreState stateFor(String workspace) {
        return workspaces.requireState(workspace);
    }

    private void checkFeatureSetCapacity(StoreState state, String ws, String featureSet) {
        WorkspaceLimits limits = workspaces.require(ws).info().limits();
        if (limits.maxFeatureSets() == null) {
            return;
        }
        int current = state.registry().featureSetCount();
        int limit = limits.maxFeatureSets();
        if (current + 1 > limit) {
            state.audit().record(ws, "REGISTER", featureSet, null, null, null, "REJECTED",
                    "workspace feature-set capacity exceeded",
                    Map.of("workspace", ws, "featureSet", featureSet,
                            "dimension", "FEATURE_SETS", "limit", limit,
                            "currentUsage", current, "requested", 1));
            throw new CapacityExceededException(ws, featureSet, null, "FEATURE_SETS",
                    limit, current, 1, Map.of("workspace", ws, "featureSet", featureSet));
        }
    }

    // ---- validation -----------------------------------------------------

    private void validateShape(String name, String entityKey, List<FeatureDef> features,
                               List<ConflictDetail> conflicts) {
        if (name == null || name.isBlank()) {
            conflicts.add(new ConflictDetail(ConflictReason.INVALID_DEFINITION, null,
                    "feature set name must be non-empty"));
        }
        if (entityKey != null && entityKey.isBlank()) {
            conflicts.add(new ConflictDetail(ConflictReason.INVALID_DEFINITION, null,
                    "entity key must be non-empty"));
        }
        if (features == null || features.isEmpty()) {
            conflicts.add(new ConflictDetail(ConflictReason.INVALID_DEFINITION, null,
                    "at least one feature is required"));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (FeatureDef f : features) {
            if (f.name() == null || f.name().isBlank() || f.type() == null
                    || f.missingPolicy() == null) {
                conflicts.add(new ConflictDetail(ConflictReason.INVALID_DEFINITION,
                        f.name(), "feature requires name, type and missingPolicy"));
                continue;
            }
            if (!seen.add(f.name())) {
                conflicts.add(new ConflictDetail(ConflictReason.DUPLICATE_FEATURE_NAME,
                        f.name(), "feature name appears more than once"));
            }
            if (f.defaultValue() != null && !f.type().matches(f.defaultValue())) {
                conflicts.add(new ConflictDetail(ConflictReason.INVALID_DEFINITION,
                        f.name(), "default value " + f.defaultValue()
                                + " is not assignable to type " + f.type()));
            }
        }
    }

    private void compareVersions(FeatureSetDef prev, List<FeatureDef> nextFeatures,
                                 Long nextTtl, List<ConflictDetail> conflicts) {
        List<FeatureDef> prevFeatures = prev.features();

        int common = Math.min(prevFeatures.size(), nextFeatures.size());
        for (int i = 0; i < common; i++) {
            FeatureDef p = prevFeatures.get(i);
            FeatureDef n = nextFeatures.get(i);
            if (!p.name().equals(n.name())) {
                conflicts.add(new ConflictDetail(ConflictReason.FEATURE_RENAMED_OR_REORDERED,
                        n.name(), "position " + i + " was '" + p.name()
                                + "' but new definition has '" + n.name()
                                + "'; features may only be appended, never renamed/reordered"));
                continue;
            }
            compareFeature(p, n, conflicts);
        }
        if (nextFeatures.size() < prevFeatures.size()) {
            for (int i = nextFeatures.size(); i < prevFeatures.size(); i++) {
                conflicts.add(new ConflictDetail(ConflictReason.FEATURE_REMOVED,
                        prevFeatures.get(i).name(),
                        "existing feature was removed in the new definition"));
            }
        }
        if (nextFeatures.size() > prevFeatures.size()) {
            Set<String> prevNames = new HashSet<>();
            prevFeatures.forEach(f -> prevNames.add(f.name()));
            for (int i = prevFeatures.size(); i < nextFeatures.size(); i++) {
                FeatureDef n = nextFeatures.get(i);
                if (prevNames.contains(n.name())) {
                    conflicts.add(new ConflictDetail(ConflictReason.FEATURE_RENAMED_OR_REORDERED,
                            n.name(), "existing feature moved to append position " + i
                                    + "; reordering is incompatible"));
                }
            }
        }
        if (nextTtl != null) {
            if (prev.ttlMillis() == null) {
                if (nextTtl <= 0) {
                    conflicts.add(new ConflictDetail(ConflictReason.INVALID_DEFINITION, null,
                            "ttlMillis must be positive"));
                }
            } else if (nextTtl < prev.ttlMillis()) {
                conflicts.add(new ConflictDetail(ConflictReason.TTL_SHORTENED, null,
                        "ttl may not be shortened: previous=" + prev.ttlMillis()
                                + "ms, requested=" + nextTtl + "ms"));
            }
        }
    }

    private void compareFeature(FeatureDef p, FeatureDef n, List<ConflictDetail> conflicts) {
        if (p.type() != n.type()) {
            conflicts.add(new ConflictDetail(ConflictReason.FEATURE_TYPE_CHANGED, n.name(),
                    "type changed from " + p.type() + " to " + n.type()));
        }
        if (p.required() != n.required()) {
            conflicts.add(new ConflictDetail(ConflictReason.REQUIRED_FLAG_CHANGED, n.name(),
                    "required flag changed from " + p.required() + " to " + n.required()));
        }
        if (!Objects.equals(normalizedDefault(p), normalizedDefault(n))) {
            conflicts.add(new ConflictDetail(ConflictReason.DEFAULT_VALUE_CHANGED, n.name(),
                    "default value changed from '" + p.defaultValue() + "' to '"
                            + n.defaultValue() + "'"));
        }
        if (p.missingPolicy() != n.missingPolicy()) {
            conflicts.add(new ConflictDetail(ConflictReason.MISSING_POLICY_CHANGED, n.name(),
                    "missing policy changed from " + p.missingPolicy() + " to "
                            + n.missingPolicy()));
        }
    }

    private Object normalizedDefault(FeatureDef f) {
        Object v = f.defaultValue();
        return v == null ? null : f.type().normalize(v);
    }

    private DefinitionConflictException conflict(StoreState state, String ws, String name,
                                                 List<ConflictDetail> conflicts) {
        state.audit().record(ws, "REGISTER_CONFLICT", name, null, null, null, "REJECTED",
                conflicts.size() + " definition conflict(s)",
                Map.of("workspace", ws, "conflicts", describe(conflicts)));
        return new DefinitionConflictException(ws, name, conflicts);
    }

    private List<Map<String, Object>> describe(List<ConflictDetail> conflicts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConflictDetail c : conflicts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", c.reason().name());
            m.put("feature", c.feature());
            m.put("detail", c.detail());
            out.add(m);
        }
        return out;
    }
}
