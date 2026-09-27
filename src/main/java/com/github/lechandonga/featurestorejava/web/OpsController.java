package com.github.lechandonga.featurestorejava.web;

import com.github.lechandonga.featurestorejava.model.AuditEntry;
import com.github.lechandonga.featurestorejava.model.CoverageReport;
import com.github.lechandonga.featurestorejava.model.FreshnessReport;
import com.github.lechandonga.featurestorejava.service.AuditLog;
import com.github.lechandonga.featurestorejava.service.ObservabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Freshness, coverage and audit-trail endpoints.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final ObservabilityService ops;
    private final AuditLog audit;

    public OpsController(ObservabilityService ops, AuditLog audit) {
        this.ops = ops;
        this.audit = audit;
    }

    @GetMapping("/freshness/{featureSet}")
    public FreshnessReport freshness(@PathVariable String featureSet,
                                     @RequestParam(required = false) Integer version,
                                     @RequestParam(required = false) Long asOf,
                                     @RequestParam(required = false) String workspace) {
        return ops.freshness(workspace, featureSet, version, asOf);
    }

    @GetMapping("/coverage/{featureSet}")
    public CoverageReport coverage(@PathVariable String featureSet,
                                   @RequestParam(required = false) Integer version,
                                   @RequestParam(required = false) Long asOf,
                                   @RequestParam(required = false) String workspace) {
        return ops.coverage(workspace, featureSet, version, asOf);
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(required = false) String workspace) {
        return workspace == null ? audit.recent(limit) : audit.recent(workspace, limit);
    }
}
