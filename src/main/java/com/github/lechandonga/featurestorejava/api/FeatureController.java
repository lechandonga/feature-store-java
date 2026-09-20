package com.github.lechandonga.featurestorejava.api;

import com.github.lechandonga.featurestorejava.domain.FeatureDefinition;
import com.github.lechandonga.featurestorejava.domain.MissingPolicy;
import com.github.lechandonga.featurestorejava.domain.ValueType;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import org.springframework.web.bind.annotation.*;

/** Registration and version evolution endpoints. */
@RestController
@RequestMapping("/api/features")
public class FeatureController {

    private final FeatureRegistry registry;

    public FeatureController(FeatureRegistry registry) {
        this.registry = registry;
    }

    public record RegisterRequest(String name, Integer version, String valueType, String missingPolicy,
                                  Object defaultValue, String description, Long ttlMs) {
    }

    public record EvolveRequest(Object defaultValue, String description, Long ttlMs) {
    }

    public record DefinitionDto(String name, int version, String valueType, String missingPolicy,
                                Object defaultValue, String description, long ttlMs, long createdAtMs) {
        static DefinitionDto from(FeatureDefinition d) {
            return new DefinitionDto(d.name(), d.version(), d.valueType().name(), d.missingPolicy().name(),
                    d.defaultValue(), d.description(), d.ttlMs(), d.createdAtMs());
        }
    }

    @PostMapping
    public DefinitionDto register(@RequestBody RegisterRequest request) {
        FeatureDefinition def = registry.register(request.name(), request.version(),
                ValueType.valueOf(request.valueType()),
                MissingPolicy.valueOf(request.missingPolicy()),
                request.defaultValue(), request.description(),
                request.ttlMs() == null ? 0L : request.ttlMs());
        return DefinitionDto.from(def);
    }

    @PostMapping("/{name}/evolve")
    public DefinitionDto evolve(@PathVariable String name, @RequestBody EvolveRequest request) {
        return DefinitionDto.from(registry.evolve(name, request.defaultValue(),
                request.description(), request.ttlMs()));
    }

    @GetMapping("/{name}")
    public Object versions(@PathVariable String name) {
        return registry.versions(name).stream().map(DefinitionDto::from).toList();
    }
}
