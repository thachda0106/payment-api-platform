package com.paymentapi.platform.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Base platform configuration — always validated.
 * Binds from env vars: {@code SERVER_PORT}, {@code LOG_LEVEL}, etc.
 */
@Validated
@ConfigurationProperties(prefix = "platform")
public class PlatformProperties {

    private final Server server = new Server();
    private final Logging logging = new Logging();
    private final Otel otel = new Otel();

    public Server getServer() { return server; }
    public Logging getLogging() { return logging; }
    public Otel getOtel() { return otel; }

    public static class Server {
        @Min(1) @Max(65535)
        private int port = 8080;
        @NotBlank
        private String host = "0.0.0.0";

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
    }

    public static class Logging {
        @NotBlank
        private String level = "info";
        @NotBlank
        private String format = "json";

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
    }

    public static class Otel {
        @NotBlank
        private String exporterEndpoint;
        @NotBlank
        private String serviceName = "unknown";
        @NotBlank
        private String serviceVersion = "0.1.0";

        public String getExporterEndpoint() { return exporterEndpoint; }
        public void setExporterEndpoint(String exporterEndpoint) { this.exporterEndpoint = exporterEndpoint; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getServiceVersion() { return serviceVersion; }
        public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
    }
}
