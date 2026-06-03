package com.paymentapi.platform.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal metadata about this service for logging and health endpoints.
 * NOT used to configure the OTel Java Agent — the Agent reads
 * {@code OTEL_SERVICE_NAME} and {@code OTEL_RESOURCE_ATTRIBUTES} directly.
 *
 * <p>This bean is for application-level introspection only
 * (e.g., populating the "service" field in probe responses).
 */
@ConfigurationProperties(prefix = "platform.telemetry")
public class TelemetryMetadataProperties {

    /** Service name — also set via OTEL_SERVICE_NAME for the Agent. */
    private String serviceName = "unknown";

    /** Service version — also set via OTEL_RESOURCE_ATTRIBUTES=service.version=... */
    private String serviceVersion = "0.1.0";

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getServiceVersion() { return serviceVersion; }
    public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
}
