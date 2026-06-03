package com.paymentapi.platform.health;

import com.paymentapi.platform.config.PlatformProperties;
import com.paymentapi.platform.telemetry.TelemetryMetadataProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Auto-configures health probe controllers and the shared dependency registry.
 * <p>
 * Services register their own dependency checks by injecting
 * {@link CachedDependencyRegistry} and calling {@code registry.register(...)}.
 */
@AutoConfiguration
@EnableConfigurationProperties({TelemetryMetadataProperties.class})
public class HealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CachedDependencyRegistry cachedDependencyRegistry() {
        return new CachedDependencyRegistry(Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnMissingBean
    public LivenessController livenessController(TelemetryMetadataProperties metadata) {
        return new LivenessController(metadata);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReadinessController readinessController(TelemetryMetadataProperties metadata,
                                                    CachedDependencyRegistry registry) {
        return new ReadinessController(metadata, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public StartupController startupController(TelemetryMetadataProperties metadata,
                                                CachedDependencyRegistry registry) {
        return new StartupController(metadata, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public LegacyProbeController legacyProbeController() {
        return new LegacyProbeController();
    }
}
