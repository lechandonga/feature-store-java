package com.github.lechandonga.featurestorejava.web;

import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.service.FeatureRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Registration and version evolution endpoints.
 */
@RestController
@RequestMapping("/api/features")
public class FeatureController {

    private final FeatureRegistry registry;

    public FeatureController(FeatureRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public FeatureSetDef register(@RequestBody RegisterRequest request,
                                  @RequestParam(required = false) String workspace) {
        return registry.register(workspace, request);
    }

    @PostMapping("/{name}/versions")
    public FeatureSetDef evolve(@PathVariable String name,
                                @RequestParam(required = false) Long ttlMillis,
                                @RequestParam(required = false) String workspace,
                                @RequestBody List<com.github.lechandonga.featurestorejava.model.FeatureDef> features) {
        return registry.evolve(workspace, name, features, ttlMillis);
    }

    @GetMapping("/{name}/versions/{version}")
    public FeatureSetDef version(@PathVariable String name, @PathVariable int version,
                                 @RequestParam(required = false) String workspace) {
        return registry.requireVersion(workspace, name, version);
    }
}
