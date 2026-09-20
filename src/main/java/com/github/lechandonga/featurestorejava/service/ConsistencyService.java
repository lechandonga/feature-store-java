package com.github.lechandonga.featurestorejava.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.github.lechandonga.featurestorejava.domain.ConflictReason;
import com.github.lechandonga.featurestorejava.domain.DiscrepancyType;
import com.github.lechandonga.featurestorejava.domain.FeatureDefinition;
import com.github.lechandonga.featurestorejava.exception.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.springframework.stereotype.Service;

/**
 * Compares, per feature, the online value with the point-in-time (offline) value for the same
 * (entity, version, asOf). Both sides are computed by the same {@link FeatureLookupService};
 * when they are evaluated at one shared time point they are definitionally equivalent, so any
 * difference is detected and classified (VALUE / PRESENCE / STATUS / VERSION mismatch) instead
 * of being masked by silently returned defaults.
 */
@Service
public class ConsistencyService {

    private final FeatureRegistry registry;
    private final FeatureLookupService lookup;
    private final DecisionLogger logger;
    private final FeatureStorage storage;

    public ConsistencyService(FeatureRegistry registry, FeatureLookupService lookup,
                              DecisionLogger logger, FeatureStorage storage) {
        this.registry = registry;
        this.lookup = lookup;
        this.logger = logger;
        this.storage = storage;
    }

    public ConsistencyReport check(Observation observation) {
        Object onlineSnapshot = storage.captureSnapshot();
        Object asOfSnapshot = storage.captureSnapshot();
        List<ConsistencyReport.Item> items = new ArrayList<>();
        List<String> names = observation.featureNames();
        List<Integer> versions = observation.versions();
        boolean anyMismatch = false;

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Integer requestedVersion = versions == null || i >= versions.size() ? null : versions.get(i);
            FeatureDefinition def = requestedVersion == null
                    ? registry.getLatest(name)
                    : registry.get(name, requestedVersion);
            if (def == null) {
                throw new DefinitionConflictException(ConflictReason.VERSION_NOT_FOUND,
                        "feature=" + name + " version=" + requestedVersion + " not registered");
            }

            FeatureValue online = lookup.lookup(def, observation.entityId(), observation.asOfMs(),
                    FeatureLookupService.LookupMode.ONLINE, onlineSnapshot);
            FeatureValue pointInTime = lookup.lookup(def, observation.entityId(), observation.asOfMs(),
                    FeatureLookupService.LookupMode.AS_OF, asOfSnapshot);

            DiscrepancyType type;
            String detail;
            if (online.status() != pointInTime.status()) {
                type = DiscrepancyType.STATUS_MISMATCH;
                detail = "online status " + online.status() + " vs point-in-time status " + pointInTime.status();
            } else if (!Objects.equals(online.value(), pointInTime.value())) {
                if (online.eventTsMs() == null || pointInTime.eventTsMs() == null) {
                    type = DiscrepancyType.PRESENCE_MISMATCH;
                    detail = "only one side resolved to a recorded value"
                            + " (online eventTs=" + online.eventTsMs()
                            + ", pointInTime eventTs=" + pointInTime.eventTsMs() + ")";
                } else {
                    type = DiscrepancyType.VALUE_MISMATCH;
                    detail = "online value " + online.value() + " (eventTs=" + online.eventTsMs()
                            + ") vs point-in-time value " + pointInTime.value()
                            + " (eventTs=" + pointInTime.eventTsMs() + ")";
                }
            } else if (online.eventTsMs() != null && !Objects.equals(online.eventTsMs(), pointInTime.eventTsMs())) {
                type = DiscrepancyType.VALUE_MISMATCH;
                detail = "same value resolved from different event times online="
                        + online.eventTsMs() + " pointInTime=" + pointInTime.eventTsMs();
            } else if (online.version() != pointInTime.version()) {
                type = DiscrepancyType.VERSION_MISMATCH;
                detail = "online resolved v" + online.version()
                        + " vs point-in-time resolved v" + pointInTime.version();
            } else {
                type = DiscrepancyType.MATCH;
                detail = "equivalent at asOf=" + observation.asOfMs();
            }
            if (type != DiscrepancyType.MATCH) {
                anyMismatch = true;
            }
            logger.log("consistency", name, def.version(), observation.entityId(), null,
                    observation.asOfMs(), type.name(), detail);
            items.add(new ConsistencyReport.Item(name, def.version(), online, pointInTime, type, detail));
        }

        ConsistencyReport.Status status = anyMismatch
                ? ConsistencyReport.Status.MISMATCH : ConsistencyReport.Status.MATCH;
        return new ConsistencyReport(status, System.currentTimeMillis(), observation, List.copyOf(items));
    }
}
