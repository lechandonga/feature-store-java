package com.github.lechandonga.featurestorejava.web;

import com.github.lechandonga.featurestorejava.model.WorkspaceRequest;
import com.github.lechandonga.featurestorejava.model.WorkspaceUsage;
import com.github.lechandonga.featurestorejava.service.WorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Workspace lifecycle endpoints: create, list and inspect usage/limits.
 * Every other endpoint accepts an optional {@code workspace} query parameter
 * to select the namespace it operates on (default: {@code default}).
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @PostMapping
    public WorkspaceUsage create(@RequestBody WorkspaceRequest request) {
        return workspaces.create(request);
    }

    @GetMapping
    public List<WorkspaceUsage> list() {
        return workspaces.list();
    }

    @GetMapping("/{name}")
    public WorkspaceUsage usage(@PathVariable String name) {
        return workspaces.usage(name);
    }
}
