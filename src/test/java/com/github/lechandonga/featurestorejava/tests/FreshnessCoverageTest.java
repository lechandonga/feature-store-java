package com.github.lechandonga.featurestorejava.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import com.github.lechandonga.featurestorejava.domain.MissingPolicy;
import com.github.lechandonga.featurestorejava.domain.ValueStatus;
import com.github.lechandonga.featurestorejava.domain.ValueType;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.*;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.junit.jupiter.api.Test;

class FreshnessCoverageTest {

    @Test
    void staleAndMissingFeaturesAreReportedExplicitly() {
        FeatureRegistry registry = new FeatureRegistry();
        FeatureStorage storage = new FeatureStorage();
        DecisionLogger logger = new DecisionLogger();
        WriteCoordinator coordinator = new WriteCoordinator();
        IngestService ingest = new IngestService(registry, storage, coordinator, logger);
        FreshnessService freshness = new FreshnessService(registry, storage, logger);

        registry.register("fresh_feat", 1, ValueType.INT, MissingPolicy.NULL, null, null, 60_000L);
        registry.register("stale_feat", 1, ValueType.INT, MissingPolicy.NULL, null, null, 1_000L);
        registry.register("empty_feat", 1, ValueType.INT, MissingPolicy.NULL, null, null, 60_000L);

        long now = System.currentTimeMillis();
        assertTrue(ingest.write("b", List.of(
                new RowInput("fresh_feat", 1, "e1", now - 1000, 1),
                new RowInput("stale_feat", 1, "e1", now - 60_000, 2))).accepted());

        FreshnessReport report = freshness.report("e1",
                List.of("fresh_feat", "stale_feat", "empty_feat"), null);
        assertEquals(3, report.checkedFeatures());
        assertEquals(1, report.staleCount());
        assertEquals(1, report.missingCount());
        assertEquals(2.0 / 3.0, report.coverageRatio(), 1e-9);
        assertEquals(FreshnessReport.Freshness.FRESH, report.items().get(0).freshness());
        assertEquals(FreshnessReport.Freshness.STALE, report.items().get(1).freshness());
        assertEquals(FreshnessReport.Freshness.MISSING, report.items().get(2).freshness());
    }

    @Test
    void expiredValueServedWithExpiredStatusNotSilentDefault() {
        FeatureRegistry registry = new FeatureRegistry();
        FeatureStorage storage = new FeatureStorage();
        DecisionLogger logger = new DecisionLogger();
        WriteCoordinator coordinator = new WriteCoordinator();
        IngestService ingest = new IngestService(registry, storage, coordinator, logger);
        FeatureLookupService lookup = new FeatureLookupService(storage, logger);

        registry.register("v", 1, ValueType.INT, MissingPolicy.DEFAULT, 0, null, 1_000L);
        assertTrue(ingest.write("b", List.of(
                new RowInput("v", 1, "e1", System.currentTimeMillis() - 60_000, 42))).accepted());

        FeatureValue fv = lookup.lookup(registry.getLatest("v"), "e1",
                System.currentTimeMillis(), FeatureLookupService.LookupMode.ONLINE);
        assertEquals(ValueStatus.EXPIRED, fv.status());
        assertEquals(42, fv.value(), "stale real value kept, not replaced by default");
    }

    @Test
    void missingPoliciesReturnDistinctExplicitStatuses() {
        FeatureRegistry registry = new FeatureRegistry();
        FeatureStorage storage = new FeatureStorage();
        DecisionLogger logger = new DecisionLogger();
        FeatureLookupService lookup = new FeatureLookupService(storage, logger);

        registry.register("d", 1, ValueType.INT, MissingPolicy.DEFAULT, 7, null, 0L);
        registry.register("n", 1, ValueType.INT, MissingPolicy.NULL, null, null, 0L);
        registry.register("x", 1, ValueType.INT, MissingPolicy.ERROR, null, null, 0L);
        long asOf = System.currentTimeMillis();

        assertEquals(ValueStatus.DEFAULTED,
                lookup.lookup(registry.getLatest("d"), "e", asOf, FeatureLookupService.LookupMode.ONLINE).status());
        assertEquals(7,
                lookup.lookup(registry.getLatest("d"), "e", asOf, FeatureLookupService.LookupMode.ONLINE).value());
        assertEquals(ValueStatus.MISSING,
                lookup.lookup(registry.getLatest("n"), "e", asOf, FeatureLookupService.LookupMode.ONLINE).status());
        assertEquals(ValueStatus.MISSING_ERROR,
                lookup.lookup(registry.getLatest("x"), "e", asOf, FeatureLookupService.LookupMode.ONLINE).status());
    }

    @Test
    void auditLogContainsEntityTimePointAndBasis() {
        FeatureRegistry registry = new FeatureRegistry();
        FeatureStorage storage = new FeatureStorage();
        DecisionLogger logger = new DecisionLogger();
        WriteCoordinator coordinator = new WriteCoordinator();
        IngestService ingest = new IngestService(registry, storage, coordinator, logger);
        FeatureLookupService lookup = new FeatureLookupService(storage, logger);
        registry.register("f", 1, ValueType.INT, MissingPolicy.NULL, null, null, 0L);
        long t = System.currentTimeMillis() - 10_000;
        ingest.write("b", List.of(new RowInput("f", 1, "ent-9", t, 3)));
        lookup.lookup(registry.getLatest("f"), "ent-9", System.currentTimeMillis(),
                FeatureLookupService.LookupMode.ONLINE);

        var entries = logger.recent(10);
        assertTrue(entries.stream().anyMatch(e -> "ent-9".equals(e.entityId())
                && "RECORDED".equals(e.decision()) && e.asOfMs() != null
                && e.basis() != null && e.basis().contains("revisionTs")));
        assertTrue(entries.stream().anyMatch(e -> "COMMITTED".equals(e.decision())));
    }
}
