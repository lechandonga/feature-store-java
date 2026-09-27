package com.github.lechandonga.featurestorejava.web;

import com.github.lechandonga.featurestorejava.model.CreateWorkspaceRequest;
import com.github.lechandonga.featurestorejava.model.WorkspaceInfo;
import com.github.lechandonga.featurestorejava.model.WorkspaceUsage;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Workspace (namespace) management, selection and capacity endpoints.
 *
 * <p>Every data endpoint selects its workspace through the
 * {@code X-Workspace} header; when absent the {@code default} workspace is
 * used, preserving the pre-workspace API exactly.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceManager workspaces;

    public WorkspaceController(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    /** Create a workspace, optionally with feature-set/points capacity limits. */
    @PostMapping
    public WorkspaceInfo create(@RequestBody CreateWorkspaceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return workspaces.create(request.name(), request.limits()).info();
    }

    /** List all workspaces (metadata and capacity limits, no data). */
    @GetMapping
    public List<WorkspaceInfo> list() {
        return workspaces.list();
    }

    /** Current usage vs limits for the workspace selected via X-Workspace. */
    @GetMapping("/usage")
    public WorkspaceUsage usage(@RequestHeader(value = "X-Workspace", required = false) String workspace) {
        return workspaces.usage(workspace);
    }

    /** Metadata of the workspace selected via X-Workspace. */
    @GetMapping("/current")
    public WorkspaceInfo current(@RequestHeader(value = "X-Workspace", required = false) String workspace) {
        return workspaces.require(workspace).info();
    }
}
