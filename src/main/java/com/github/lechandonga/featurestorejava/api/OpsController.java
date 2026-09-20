package com.github.lechandonga.featurestorejava.api;

import java.util.List;
import com.github.lechandonga.featurestorejava.service.DecisionLogger;
import com.github.lechandonga.featurestorejava.service.FreshnessReport;
import com.github.lechandonga.featurestorejava.service.FreshnessService;
import org.springframework.web.bind.annotation.*;

/** Freshness/coverage observation and structured audit log endpoints. */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final FreshnessService freshness;
    private final DecisionLogger logger;

    public OpsController(FreshnessService freshness, DecisionLogger logger) {
        this.freshness = freshness;
        this.logger = logger;
    }

    public record FreshnessRequest(String entityId, List<String> features, List<Integer> versions) {
    }

    @PostMapping("/freshness")
    public FreshnessReport freshness(@RequestBody FreshnessRequest request) {
        return freshness.report(request.entityId(), request.features(), request.versions());
    }

    @GetMapping("/audit")
    public Object audit(@RequestParam(defaultValue = "50") int limit) {
        return logger.recent(limit);
    }
}
