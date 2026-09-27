package com.github.lechandonga.featurestorejava.workspace;

import com.github.lechandonga.featurestorejava.error.WorkspaceConflictException;
import com.github.lechandonga.featurestorejava.error.WorkspaceNotFoundException;
import com.github.lechandonga.featurestorejava.model.WorkspaceInfo;
import com.github.lechandonga.featurestorejava.model.WorkspaceLimits;
import com.github.lechandonga.featurestorejava.model.WorkspaceUsage;
import com.github.lechandonga.featurestorejava.service.StoreState;
import com.github.lechandonga.featurestorejava.service.TimeSource;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates, lists and selects workspaces; each workspace owns an independent
 * {@link StoreState} (registry, offline/online stores, audit ring, backfill
 * idempotency table) and independent capacity limits/usage.
 *
 * <p>Workspaces are isolated namespaces: same-named feature sets in different
 * workspaces are unrelated, and no lock, point or correction is ever shared.
 * The {@link #DEFAULT_WORKSPACE default} workspace always exists and needs no
 * {@code X-Workspace} selection, which keeps every pre-workspace call working.
 */
public class WorkspaceManager {

    public static final String DEFAULT_WORKSPACE = "default";

    /** One workspace: metadata plus its fully isolated store state. */
    public record Instance(WorkspaceInfo info, StoreState state) {

        public String name() {
            return info.name();
        }
    }

    private final TimeSource time;
    private final ConcurrentMap<String, Instance> workspaces = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Instance> createHook;

    /**
     * Register a callback invoked once after a workspace is created through
     * {@link #create}. Used by persistence to attach the commit hook and save
     * the new workspace list immediately.
     */
    public void setCreateHook(java.util.function.Consumer<Instance> createHook) {
        this.createHook = createHook;
    }

    public WorkspaceManager(TimeSource time) {
        this.time = time;
    }

    /** Resolve a caller-supplied workspace name; null/blank means default. */
    public static String resolve(String workspace) {
        return workspace == null || workspace.isBlank() ? DEFAULT_WORKSPACE : workspace.trim();
    }

    /**
     * Ensure the default workspace exists. Called at startup before snapshot
     * restore, so pre-workspace data always lands in {@code default}.
     */
    public void initDefault() {
        workspaces.computeIfAbsent(DEFAULT_WORKSPACE,
                name -> new Instance(
                        new WorkspaceInfo(name, time.now(), new WorkspaceLimits(null, null)),
                        new StoreState()));
    }

    /**
     * Build a manager whose default workspace reuses an already constructed
     * state. Used by backward-compatible service constructors and unit-test
     * wiring where a single in-memory state is shared directly.
     */
    public static WorkspaceManager wrappingDefault(StoreState state, TimeSource time) {
        WorkspaceManager manager = new WorkspaceManager(time);
        manager.restore(new WorkspaceInfo(DEFAULT_WORKSPACE, time.now(),
                new WorkspaceLimits(null, null)), state, null);
        return manager;
    }

    public Instance require(String workspace) {
        Instance ws = workspaces.get(resolve(workspace));
        if (ws == null) {
            throw new WorkspaceNotFoundException(resolve(workspace));
        }
        return ws;
    }

    /** Resolve and return the isolated store state for a workspace. */
    public StoreState requireState(String workspace) {
        return require(workspace).state();
    }

    /**
     * Create a workspace with optional capacity limits. Creating a workspace
     * that already exists fails with a distinguishable conflict; nothing is
     * altered in that case.
     */
    public Instance create(String requestedName, WorkspaceLimits limits) {
        String name = resolve(requestedName);
        validateName(requestedName);
        if (limits != null) {
            validateLimits(limits);
        }
        WorkspaceInfo info = new WorkspaceInfo(name, time.now(),
                limits == null ? new WorkspaceLimits(null, null) : limits);
        // atomic create-or-reject; no lock needed because computeIfAbsent is
        // atomic and the rejected branch performs no mutation
        Instance[] created = new Instance[1];
        workspaces.compute(name, (k, existing) -> {
            if (existing != null) {
                throw new WorkspaceConflictException(name,
                        "workspace '" + name + "' already exists (createdAt="
                                + existing.info().createdAt() + ")");
            }
            Instance ws = new Instance(info, new StoreState());
            created[0] = ws;
            return ws;
        });
        Instance ws = created[0];
        ws.state().audit().record("WORKSPACE_CREATE", null, null, null, null,
                "ACCEPTED", "workspace created",
                Map.of("workspace", name,
                        "maxFeatureSets", String.valueOf(limits == null ? null : limits.maxFeatureSets()),
                        "maxPoints", String.valueOf(limits == null ? null : limits.maxPoints())));
        java.util.function.Consumer<Instance> hook = createHook;
        if (hook != null) {
            hook.accept(ws);
        }
        return ws;
    }

    public List<WorkspaceInfo> list() {
        return workspaces.values().stream()
                .map(Instance::info)
                .sorted(Comparator.comparing(WorkspaceInfo::name))
                .toList();
    }

    /** Current usage and configured limits of one workspace. */
    public WorkspaceUsage usage(String workspace) {
        Instance ws = require(workspace);
        ws.state().lock().readLock().lock();
        try {
            int featureSets = ws.state().registry().featureSetCount();
            long points = ws.state().offline().totalPoints();
            return new WorkspaceUsage(ws.name(), featureSets, points, ws.info().limits());
        } finally {
            ws.state().lock().readLock().unlock();
        }
    }

    /**
     * Name of the workspace that currently owns the given point id, or null.
     * Used to detect and reject cross-workspace backfill targeting.
     */
    public String workspaceOfPoint(String pointId) {
        if (pointId == null) {
            return null;
        }
        for (Instance ws : workspaces.values()) {
            ws.state().lock().readLock().lock();
            try {
                if (ws.state().offline().getPoint(pointId) != null) {
                    return ws.name();
                }
            } finally {
                ws.state().lock().readLock().unlock();
            }
        }
        return null;
    }

    /**
     * Re-register a workspace during snapshot restore. Pre-existing data
     * without a workspace record belongs to {@code default}; explicit metadata
     * from the snapshot restores non-default workspaces and their limits.
     */
    public void restore(WorkspaceInfo info, Map<String, List<String>> correlations) {
        restore(info, new StoreState(), correlations);
    }

    /** Restore a workspace reusing an already-built state (default workspace). */
    public void restore(WorkspaceInfo info, StoreState state,
                        Map<String, List<String>> correlations) {
        workspaces.put(info.name(), new Instance(info, state));
        if (correlations != null) {
            correlations.forEach(state::rememberCorrelation);
        }
    }

    /** All workspace instances; used for full snapshot persistence. */
    public List<Instance> all() {
        return workspaces.values().stream()
                .sorted(Comparator.comparing(Instance::name))
                .toList();
    }

    private void validateName(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            throw new IllegalArgumentException("workspace name must be non-empty");
        }
        String name = requestedName.trim();
        if (name.length() > 64 || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(
                    "workspace name '" + name + "' must be 1-64 chars of letters, digits, "
                            + "'.', '_' or '-' and start with a letter or digit");
        }
    }

    private void validateLimits(WorkspaceLimits limits) {
        if (limits.maxFeatureSets() != null && limits.maxFeatureSets() <= 0) {
            throw new IllegalArgumentException("maxFeatureSets must be positive");
        }
        if (limits.maxPoints() != null && limits.maxPoints() <= 0) {
            throw new IllegalArgumentException("maxPoints must be positive");
        }
    }
}
