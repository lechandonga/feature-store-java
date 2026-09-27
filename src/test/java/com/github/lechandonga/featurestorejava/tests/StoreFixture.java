package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.model.FeatureDef;
import com.github.lechandonga.featurestorejava.model.MissingPolicy;
import com.github.lechandonga.featurestorejava.model.ValueType;
import com.github.lechandonga.featurestorejava.service.AuditLog;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import com.github.lechandonga.featurestorejava.service.ConsistencyService;
import com.github.lechandonga.featurestorejava.service.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.ObservabilityService;
import com.github.lechandonga.featurestorejava.service.ReadService;
import com.github.lechandonga.featurestorejava.service.StoreState;
import com.github.lechandonga.featurestorejava.service.WriteService;

import java.util.List;

/**
 * Wires a fresh, fully in-memory feature store for unit tests.
 */
public class StoreFixture {

    public static final long T0 = 1_000_000L;

    public final FixedTimeSource clock = new FixedTimeSource(T0);
    public final AuditLog audit = new AuditLog();
    public final StoreState state = new StoreState(audit);
    public final FeatureRegistry registry = new FeatureRegistry(state, audit, clock);
    public final WriteService writes = new WriteService(state, registry, audit, clock);
    public final ReadService reads = new ReadService(state, registry, clock);
    public final BackfillService backfills = new BackfillService(state, registry, audit, clock);
    public final ConsistencyService consistency = new ConsistencyService(reads, registry);
    public final ObservabilityService ops = new ObservabilityService(state, registry, reads, clock);

    public static FeatureDef feature(String name, ValueType type, boolean required,
                                     Object defaultValue, MissingPolicy policy) {
        return new FeatureDef(name, type, required, defaultValue, policy, name + " feature");
    }

    public static List<FeatureDef> sampleFeatures() {
        return List.of(
                feature("age", ValueType.LONG, true, 0L, MissingPolicy.DEFAULT),
                feature("score", ValueType.DOUBLE, false, null, MissingPolicy.NULL));
    }
}
