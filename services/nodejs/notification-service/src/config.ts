/**
 * Application configuration loaded from environment variables.
 */

export const config = {
  service: {
    name: process.env.OTEL_SERVICE_NAME || "notification-service",
    port: parseInt(process.env.PORT || "3001", 10),
    host: process.env.HOST || "0.0.0.0",
  },

  database: {
    url: process.env.DATABASE_URL || "postgresql://payment:payment@localhost:5432/notification_db",
  },

  kafka: {
    brokers: (process.env.KAFKA_BROKERS || "localhost:9092").split(","),
    consumerGroup: "notification-service",
  },

  observability: {
    otelEndpoint: process.env.OTEL_EXPORTER_OTLP_ENDPOINT || "http://localhost:4317",
  },

  email: {
    host: process.env.SMTP_HOST || "localhost",
    port: parseInt(process.env.SMTP_PORT || "1025", 10),
    from: process.env.EMAIL_FROM || "noreply@payment-api.local",
  },
};
