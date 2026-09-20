package com.github.lechandonga.featurestorejava.registry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import com.github.lechandonga.featurestorejava.domain.ConflictReason;
import com.github.lechandonga.featurestorejava.domain.FeatureDefinition;
import com.github.lechandonga.featurestorejava.domain.MissingPolicy;
import com.github.lechandonga.featurestorejava.domain.ValueType;
import com.github.lechandonga.featurestorejava.exception.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.service.ValueTypeSupport;
import org.springframework.stereotype.Service;

/**
 * Stores feature definitions and enforces compatible version evolution.
 *
 * Compatibility rules:
 *  - version is an explicit integer; duplicates are rejected (VERSION_ALREADY_EXISTS)
 *  - valueType is immutable for a feature (TYPE_CHANGED)
 *  - missingPolicy is immutable for a feature (MISSING_POLICY_CHANGED)
 *  - defaultValue must match valueType (DEFAULT_VALUE_TYPE_MISMATCH)
 *  - description and ttlMs may change compatibly; defaultValue may change but keeps the same type
 */
@Service
public class FeatureRegistry {

    private final Map<String, NavigableMap<Integer, FeatureDefinition>> byFeature = new ConcurrentHashMap<>();

    public FeatureDefinition register(String name, Integer version, ValueType valueType,
                                      MissingPolicy missingPolicy, Object defaultValue,
                                      String description, long ttlMs) {
        if (name == null || name.isBlank()) {
            throw new DefinitionConflictException(ConflictReason.INVALID_DEFINITION, "feature name must not be blank");
        }
        if (valueType == null || missingPolicy == null) {
            throw new DefinitionConflictException(ConflictReason.INVALID_DEFINITION,
                    "valueType and missingPolicy are required for feature " + name);
        }
        if (ttlMs < 0) {
            throw new DefinitionConflictException(ConflictReason.INVALID_DEFINITION,
                    "ttlMs must be >= 0 for feature " + name);
        }
        if (missingPolicy == MissingPolicy.DEFAULT && !ValueTypeSupport.matches(defaultValue, valueType)) {
            throw new DefinitionConflictException(ConflictReason.DEFAULT_VALUE_TYPE_MISMATCH,
                    "default value " + defaultValue + " is not assignable to " + valueType + " for feature " + name);
        }
        if (defaultValue != null && !ValueTypeSupport.matches(defaultValue, valueType)) {
            throw new DefinitionConflictException(ConflictReason.DEFAULT_VALUE_TYPE_MISMATCH,
                    "default value " + defaultValue + " is not assignable to " + valueType + " for feature " + name);
        }

        NavigableMap<Integer, FeatureDefinition> versions =
                byFeature.computeIfAbsent(name, k -> new ConcurrentSkipListMap<>());
        synchronized (versions) {
            FeatureDefinition existingLatest = versions.isEmpty() ? null : versions.lastEntry().getValue();
            if (existingLatest != null) {
                if (existingLatest.valueType() != valueType) {
                    throw new DefinitionConflictException(ConflictReason.TYPE_CHANGED,
                            "feature " + name + " type " + existingLatest.valueType()
                                    + " cannot change to " + valueType);
                }
                if (existingLatest.missingPolicy() != missingPolicy) {
                    throw new DefinitionConflictException(ConflictReason.MISSING_POLICY_CHANGED,
                            "feature " + name + " missingPolicy " + existingLatest.missingPolicy()
                                    + " cannot change to " + missingPolicy);
                }
            }
            int targetVersion = version == null
                    ? (versions.isEmpty() ? 1 : versions.lastKey() + 1)
                    : version;
            if (versions.containsKey(targetVersion)) {
                throw new DefinitionConflictException(ConflictReason.VERSION_ALREADY_EXISTS,
                        "feature " + name + " version " + targetVersion + " already exists");
            }
            Object normalizedDefault = defaultValue == null ? null
                    : ValueTypeSupport.normalize(defaultValue, valueType);
            FeatureDefinition definition = new FeatureDefinition(name, targetVersion, valueType,
                    missingPolicy, normalizedDefault, description, ttlMs, System.currentTimeMillis());
            versions.put(targetVersion, definition);
            return definition;
        }
    }

    /** Create the next compatible version: only defaultValue (same type), description and ttlMs may change. */
    public FeatureDefinition evolve(String name, Object newDefault, String description, Long ttlMs) {
        FeatureDefinition latest = getLatestRequired(name);
        Object nextDefault = newDefault != null ? newDefault : latest.defaultValue();
        if (nextDefault != null && !ValueTypeSupport.matches(nextDefault, latest.valueType())) {
            throw new DefinitionConflictException(ConflictReason.DEFAULT_VALUE_TYPE_MISMATCH,
                    "new default value " + newDefault + " is not assignable to "
                            + latest.valueType() + " for feature " + name);
        }
        long nextTtl = ttlMs != null ? ttlMs : latest.ttlMs();
        return register(name, latest.version() + 1, latest.valueType(), latest.missingPolicy(),
                nextDefault, description != null ? description : latest.description(), nextTtl);
    }

    public FeatureDefinition get(String name, int version) {
        NavigableMap<Integer, FeatureDefinition> versions = byFeature.get(name);
        return versions == null ? null : versions.get(version);
    }

    public FeatureDefinition getLatest(String name) {
        NavigableMap<Integer, FeatureDefinition> versions = byFeature.get(name);
        return versions == null || versions.isEmpty() ? null : versions.lastEntry().getValue();
    }

    public List<FeatureDefinition> versions(String name) {
        NavigableMap<Integer, FeatureDefinition> versions = byFeature.get(name);
        return versions == null ? List.of() : versions.values().stream()
                .sorted(Comparator.comparingInt(FeatureDefinition::version)).toList();
    }

    public List<FeatureDefinition> latestAll() {
        return byFeature.values().stream()
                .filter(v -> !v.isEmpty())
                .map(v -> v.lastEntry().getValue())
                .toList();
    }

    private FeatureDefinition getLatestRequired(String name) {
        FeatureDefinition latest = getLatest(name);
        if (latest == null) {
            throw new DefinitionConflictException(ConflictReason.FEATURE_NOT_FOUND,
                    "feature " + name + " is not registered");
        }
        return latest;
    }
}
