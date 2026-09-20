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

    public ConsistencyReport check(String featureSet, Integer version,
                                   List<EntityAtPoint> targets) {
        FeatureSetDef def = registry.requireVersion(featureSet, version);
        List<ConsistencyIssue> issues = new ArrayList<>();
        if (targets == null) {
            targets = List.of();
        }
        for (EntityAtPoint target : targets) {
            OnlineResult online = reads.online(def.name(), def.version(),
                    target.entity(), target.asOfTime());
            OfflineResult offline = reads.offline(def.name(), def.version(),
                    List.of(target)).get(0);

            Set<String> featureNames = new LinkedHashSet<>();
            featureNames.addAll(online.features().keySet());
            featureNames.addAll(offline.features().keySet());

            for (String feature : featureNames) {
                FeatureValue o = online.features().get(feature);
                FeatureValue f = offline.features().get(feature);
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
