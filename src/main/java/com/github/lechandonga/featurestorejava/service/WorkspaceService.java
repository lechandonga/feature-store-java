package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.error.WorkspaceConflictException;
import com.github.lechandonga.featurestorejava.model.WorkspaceDef;
import com.github.lechandonga.featurestorejava.model.WorkspaceRequest;
import com.github.lechandonga.featurestorejava.model.WorkspaceUsage;

import java.util.List;
import java.util.Map;

/**
 * Workspace (namespace) lifecycle: create, list and inspect usage/limits.
 *
 * <p>Every workspace isolates feature set definitions, versions, stored
 * points, corrections, backfill idempotency records, audit entries and
 * freshness/coverage statistics from every other workspace. The
 * {@value StoreState#DEFAULT_WORKSPACE} workspace always exists and serves
 * every call that does not name a workspace, so pre-workspace clients keep
 * working unchanged.
 */
public class WorkspaceService {

    private final StoreState state;
    private final AuditLog audit;
    private final TimeSource time;

    public WorkspaceService(StoreState state, AuditLog audit, TimeSource time) {
        this.state = state;
        this.audit = audit;
        this.time = time;
    }

    public WorkspaceUsage create(WorkspaceRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("workspace name must be non-empty");
        }
        String name = request.name().trim();
        if (name.contains("#") || name.contains("|")) {
            throw new IllegalArgumentException(
                    "workspace name must not contain '#' or '|'");
        }
        if (request.maxFeatureSets() != null && request.maxFeatureSets() <= 0) {
            throw new IllegalArgumentException("maxFeatureSets must be positive");
        }
        if (request.maxPoints() != null && request.maxPoints() <= 0) {
            throw new IllegalArgumentException("maxPoints must be positive");
        }
        WorkspaceDef def = new WorkspaceDef(name, request.maxFeatureSets(),
                request.maxPoints(), time.now(), request.description());
        if (!state.createWorkspace(def)) {
            throw new WorkspaceConflictException(name);
        }
        audit.record(name, "WORKSPACE_CREATE", null, null, null, null, "ACCEPTED",
                "workspace created",
                Map.of("maxFeatureSets", String.valueOf(request.maxFeatureSets()),
                        "maxPoints", String.valueOf(request.maxPoints())));
        state.notifyCommit(); // persist the workspace list
        return state.usage(name);
    }

    public List<WorkspaceUsage> list() {
        return state.workspaceNames().stream().map(state::usage).toList();
    }

    /** Usage and limits of one workspace; 404-equivalent if it does not exist. */
    public WorkspaceUsage usage(String name) {
        return state.usage(StoreState.normalizeWorkspace(name));
    }
}
