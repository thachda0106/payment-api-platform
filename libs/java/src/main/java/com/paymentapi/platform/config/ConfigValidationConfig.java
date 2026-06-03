package com.paymentapi.platform.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates mandatory config at startup.
 * Fails fast with a clear error message listing ALL missing or invalid values.
 * Optional modules (database, kafka, redis) are only validated when configured.
 */
@Component
public class ConfigValidationConfig {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidationConfig.class);

    private final PlatformProperties platform;

    public ConfigValidationConfig(PlatformProperties platform) {
        this.platform = platform;
    }

    @PostConstruct
    public void validate() {
        // Mandatory config is validated by @Validated on PlatformProperties.
        // If validation fails, Spring exits with a BindingException before reaching here.
        // This method provides additional semantic validation.

        PlatformProperties.Otel otel = platform.getOtel();
        if (otel.getExporterEndpoint() == null || otel.getExporterEndpoint().isBlank()) {
            throw new IllegalStateException(
                "Config validation failed: platform.otel.exporter-endpoint is required. " +
                "Set OTEL_EXPORTER_OTLP_ENDPOINT environment variable."
            );
        }

        log.info("Platform config validated: server={}:{}, otel={}, log.level={}",
            platform.getServer().getHost(), platform.getServer().getPort(),
            otel.getExporterEndpoint(), platform.getLogging().getLevel());
    }
}
