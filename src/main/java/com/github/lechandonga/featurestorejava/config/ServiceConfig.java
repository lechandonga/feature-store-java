package com.github.lechandonga.featurestorejava.config;

import com.github.lechandonga.featurestorejava.service.AuditLog;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import com.github.lechandonga.featurestorejava.service.ConsistencyService;
import com.github.lechandonga.featurestorejava.service.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.ObservabilityService;
import com.github.lechandonga.featurestorejava.service.ReadService;
import com.github.lechandonga.featurestorejava.service.StoreState;
import com.github.lechandonga.featurestorejava.service.TimeSource;
import com.github.lechandonga.featurestorejava.service.WriteService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the in-process feature store beans as a single consistent graph.
 */
@Configuration
public class ServiceConfig {

    @Bean
    public TimeSource timeSource() {
        return new TimeSource();
    }

    @Bean
    public AuditLog auditLog() {
        return new AuditLog();
    }

    @Bean
    public StoreState storeState() {
        return new StoreState();
    }

    @Bean
    public FeatureRegistry featureRegistry(StoreState state, AuditLog audit, TimeSource time) {
        return new FeatureRegistry(state, audit, time);
    }

    @Bean
    public WriteService writeService(StoreState state, FeatureRegistry registry, AuditLog audit, TimeSource time) {
        return new WriteService(state, registry, audit, time);
    }

    @Bean
    public ReadService readService(StoreState state, FeatureRegistry registry, TimeSource time) {
        return new ReadService(state, registry, time);
    }

    @Bean
    public BackfillService backfillService(StoreState state, FeatureRegistry registry, AuditLog audit,
                                           TimeSource time) {
        return new BackfillService(state, registry, audit, time);
    }

    @Bean
    public ConsistencyService consistencyService(ReadService reads, FeatureRegistry registry) {
        return new ConsistencyService(reads, registry);
    }

    @Bean
    public ObservabilityService observabilityService(StoreState state, FeatureRegistry registry,
                                                     ReadService reads, TimeSource time) {
        return new ObservabilityService(state, registry, reads, time);
    }
}
