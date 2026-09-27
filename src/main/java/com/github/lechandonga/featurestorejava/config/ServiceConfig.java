package com.github.lechandonga.featurestorejava.config;

import com.github.lechandonga.featurestorejava.service.AuditLog;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import com.github.lechandonga.featurestorejava.service.ConsistencyService;
import com.github.lechandonga.featurestorejava.service.FeatureRegistry;
import com.github.lechandonga.featurestorejava.service.ObservabilityService;
import com.github.lechandonga.featurestorejava.service.ReadService;
import com.github.lechandonga.featurestorejava.service.TimeSource;
import com.github.lechandonga.featurestorejava.service.WriteService;
import com.github.lechandonga.featurestorejava.workspace.WorkspaceManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the in-process feature store beans as a single consistent graph.
 *
 * <p>All domain services resolve their data through the singleton
 * {@link WorkspaceManager}; each workspace owns an isolated store state. The
 * default workspace exists from startup, and persistence attaches commit hooks
 * to every workspace (including ones created later).
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
    public WorkspaceManager workspaceManager(TimeSource time) {
        WorkspaceManager manager = new WorkspaceManager(time);
        manager.initDefault();
        return manager;
    }

    @Bean
    public FeatureRegistry featureRegistry(WorkspaceManager workspaces, TimeSource time) {
        return new FeatureRegistry(workspaces, time);
    }

    @Bean
    public WriteService writeService(WorkspaceManager workspaces, FeatureRegistry registry,
                                     TimeSource time) {
        return new WriteService(workspaces, registry, time);
    }

    @Bean
    public ReadService readService(WorkspaceManager workspaces, FeatureRegistry registry,
                                   TimeSource time) {
        return new ReadService(workspaces, registry, time);
    }

    @Bean
    public BackfillService backfillService(WorkspaceManager workspaces, FeatureRegistry registry,
                                           TimeSource time) {
        return new BackfillService(workspaces, registry, time);
    }

    @Bean
    public ConsistencyService consistencyService(ReadService reads, FeatureRegistry registry) {
        return new ConsistencyService(reads, registry);
    }

    @Bean
    public ObservabilityService observabilityService(WorkspaceManager workspaces,
                                                     FeatureRegistry registry,
                                                     ReadService reads, TimeSource time) {
        return new ObservabilityService(workspaces, registry, reads, time);
    }
}
