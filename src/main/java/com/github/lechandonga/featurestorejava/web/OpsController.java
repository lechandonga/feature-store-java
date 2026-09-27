package com.github.lechandonga.featurestorejava.web;

import com.github.lechandonga.featurestorejava.model.AuditEntry;
import com.github.lechandonga.featurestorejava.model.CoverageReport;
import com.github.lechandonga.featurestorejava.model.FreshnessReport;
import com.github.lechandonga.featurestorejava.service.ObservabilityService;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Freshness, coverage and audit-trail endpoints. All are scoped to the
 * caller-selected workspace ({@code X-Workspace}); audit entries from other
 * workspaces are never returned.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final ObservabilityService ops;
    private final WorkspaceManager workspaces;

    public OpsController(ObservabilityService ops, WorkspaceManager workspaces) {
        this.ops = ops;
        this.workspaces = workspaces;
    }

    @GetMapping("/freshness/{featureSet}")
    public FreshnessReport freshness(@RequestHeader(value = "X-Workspace", required = false) String workspace,
                                     @PathVariable String featureSet,
                                     @RequestParam(required = false) Integer version,
                                     @RequestParam(required = false) Long asOf) {
        return ops.freshness(workspace, featureSet, version, asOf);
    }

    @GetMapping("/coverage/{featureSet}")
    public CoverageReport coverage(@RequestHeader(value = "X-Workspace", required = false) String workspace,
                                   @PathVariable String featureSet,
                                   @RequestParam(required = false) Integer version,
                                   @RequestParam(required = false) Long asOf) {
        return ops.coverage(workspace, featureSet, version, asOf);
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestHeader(value = "X-Workspace", required = false) String workspace,
                                  @RequestParam(defaultValue = "50") int limit) {
        return workspaces.requireState(workspace).audit().recent(limit);
    }
}
