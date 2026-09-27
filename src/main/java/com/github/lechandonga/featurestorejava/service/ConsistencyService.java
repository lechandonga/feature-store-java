package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.ConsistencyIssue;
import com.github.lechandonga.featurestorejava.model.ConsistencyReport;
import com.github.lechandonga.featurestorejava.model.DifferenceType;
import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.FeatureValue;
import com.github.lechandonga.featurestorejava.model.OfflineResult;
import com.github.lechandonga.featurestorejava.model.OnlineResult;
import com.github.lechandonga.featurestorejava.model.ValueStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Online/offline equivalence verification under one feature set version.
 *
 * <p>For every (entity, asOf) target both paths are executed and compared
 * feature by feature. Because both paths resolve through the same routine and
 * data, a healthy system always reports {@code equivalent=true}. Differences
 * are classified into {@link DifferenceType}s.
 *
 * <p>One expected, explainable difference is distinguished: the online path
 * applies the freshness TTL and may mark a value {@code EXPIRED}, while the
 * offline point-in-time path deliberately returns the value that was valid at
 * T. That is reported as {@code STATUS_MISMATCH} with both statuses named, so
 * it is never silently swallowed nor confused with a real value divergence.
 */
public class ConsistencyService {

    private final ReadService reads;
    private final FeatureRegistry registry;

    public ConsistencyService(ReadService reads, FeatureRegistry registry) {
        this.reads = reads;
        this.registry = registry;
    }

    /** Check within the default workspace (pre-workspace call sites). */
    public ConsistencyReport check(String featureSet, Integer version,
                                   List<EntityAtPoint> targets) {
        return check(StoreState.DEFAULT_WORKSPACE, featureSet, version, targets);
    }

    public ConsistencyReport check(String workspace, String featureSet, Integer version,
                                   List<EntityAtPoint> targets) {
        String ws = StoreState.normalizeWorkspace(workspace);
        FeatureSetDef def = registry.requireVersion(ws, featureSet, version);
        List<ConsistencyIssue> issues = new ArrayList<>();
        if (targets == null) {
            targets = List.of();
        }
        for (EntityAtPoint target : targets) {
            // both views come from one snapshot / one read-lock critical section
            ReadService.OnlineOfflinePair pair =
                    reads.resolveBoth(ws, def, target.entity(), target.asOfTime());
            Map<String, FeatureValue> onlineMap = pair.online();
            Map<String, FeatureValue> offlineMap = pair.offline();

            Set<String> featureNames = new LinkedHashSet<>();
            featureNames.addAll(onlineMap.keySet());
            featureNames.addAll(offlineMap.keySet());

            for (String feature : featureNames) {
                FeatureValue o = onlineMap.get(feature);
                FeatureValue f = offlineMap.get(feature);
                ConsistencyIssue issue = compare(target, feature, o, f);
                if (issue != null) {
                    issues.add(issue);
                }
            }
        }
        return new ConsistencyReport(def.name(), def.version(), targets.size(),
                issues.isEmpty(), List.copyOf(issues));
    }

    private ConsistencyIssue compare(EntityAtPoint target, String feature,
                                     FeatureValue o, FeatureValue f) {
        if (o == null) {
            return new ConsistencyIssue(target.entity(), target.asOfTime(), feature,
                    DifferenceType.ONLY_OFFLINE, null, status(f), null, f.value());
        }
        if (f == null) {
            return new ConsistencyIssue(target.entity(), target.asOfTime(), feature,
                    DifferenceType.ONLY_ONLINE, status(o), null, o.value(), null);
        }
        if (!Objects.equals(o.value(), f.value())) {
            return new ConsistencyIssue(target.entity(), target.asOfTime(), feature,
                    DifferenceType.VALUE_MISMATCH, status(o), status(f), o.value(), f.value());
        }
        if (!Objects.equals(o.pointId(), f.pointId())) {
            return new ConsistencyIssue(target.entity(), target.asOfTime(), feature,
                    DifferenceType.POINT_MISMATCH, status(o), status(f), o.value(), f.value());
        }
        if (o.status() != f.status()) {
            // EXPIRED online vs PRESENT offline at a stale as-of point is the
            // expected TTL effect; it is still surfaced, explicitly and explainably,
            // rather than being silently swallowed
            return new ConsistencyIssue(target.entity(), target.asOfTime(), feature,
                    DifferenceType.STATUS_MISMATCH, status(o), status(f), o.value(), f.value());
        }
        return null;
    }

    private static String status(FeatureValue v) {
        return v == null ? null : v.status().name();
    }
}
