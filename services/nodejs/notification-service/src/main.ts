"""
Notification Service
====================
Generic domain service for push, email, SMS, and in-app notifications.
Consumes events from Kafka and delivers via appropriate channels.

Uses @payment-api/platform-libs for telemetry, health probes, and config.
"""

import Fastify from "fastify";
import { loadConfig } from "@payment-api/platform-libs/config";
import { initTelemetry } from "@payment-api/platform-libs/telemetry";
import { healthPlugin, CachedDependencyRegistry } from "@payment-api/platform-libs/health";

async function start() {
  // 1. Load config (fails fast on missing required values)
  const config = loadConfig();

  // 2. Initialize OTel (MUST be called before creating Fastify server)
  const sdk = initTelemetry(
    config.otel.serviceName,
    config.otel.exporterEndpoint,
    config.otel.serviceVersion,
  );

  // 3. Create Fastify server
  const app = Fastify({
    logger: {
      level: config.logging.level,
      transport: config.logging.format === "text"
        ? { target: "pino-pretty" }
        : undefined,
    },
  });

  // 4. Cached dependency registry
  const registry = new CachedDependencyRegistry(5);

  // Register checks if modules are configured
  if (config.database) {
    // Database check will be registered when pool is created (Phase 7)
  }
  if (config.kafka) {
    // Kafka check will be registered when client is created (Phase 7)
  }

  // 5. Register plugins
  await app.register(healthPlugin, {
    serviceName: config.otel.serviceName,
    version: config.otel.serviceVersion,
    registry,
  });

  // 6. Backward compat redirects
  app.get("/health", async (_req, reply) => {
    return reply.redirect(301, "/liveness");
  });
  app.get("/ready", async (_req, reply) => {
    return reply.redirect(301, "/readiness");
  });

  // 7. Graceful shutdown
  const shutdown = async () => {
    app.log.info("Notification Service shutting down gracefully...");
    await app.close();
    await sdk.shutdown();
    process.exit(0);
  };
  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);

  // 8. Start server
  try {
    await app.listen({ port: config.server.port, host: config.server.host });
    app.log.info(`Notification Service listening on ${config.server.host}:${config.server.port}`);
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }
}

start();
