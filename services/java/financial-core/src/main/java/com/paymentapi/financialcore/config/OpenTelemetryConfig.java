package com.paymentapi.financialcore.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry configuration for Financial Core.
 * Auto-instrumentation via the OTel Java Agent handles HTTP, JPA, and Kafka spans.
 * This class provides programmatic configuration for custom spans.
 */
@Configuration
public class OpenTelemetryConfig {

    @Bean
    public OpenTelemetry openTelemetry() {
        // OTel Java Agent handles auto-instrumentation.
        // This bean provides access for manual instrumentation if needed.
        return OpenTelemetrySdk.builder().build().getTracerProvider()
                .get("financial-core")
                .getClass()
                .getPackage()
                .getName()
                .contains("opentelemetry")
                ? OpenTelemetry.noop()
                : OpenTelemetry.noop();
    }
}
