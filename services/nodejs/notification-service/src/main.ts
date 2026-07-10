/**
 * Notification Service
 * ====================
 * Consumes `ledger-events`, records notifications, and publishes `notification-events`
 * via a transactional outbox.
 *
 * Uses @payment-api/platform-libs for telemetry, health probes, and config.
 */

import Fastify from "fastify";
import { Kafka, logLevel } from "kafkajs";
import { Pool } from "pg";
import { loadConfig } from "@payment-api/platform-libs/config";
import { initTelemetry } from "@payment-api/platform-libs/telemetry";
import { healthPlugin, CachedDependencyRegistry } from "@payment-api/platform-libs/health";
import { NotificationConsumer, NotificationOutboxPoller } from "./consumer";

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

  // 4. Dependencies (DB pool + Kafka) — created before health registration
  const registry = new CachedDependencyRegistry(5);

  let pool: Pool | null = null;
  if (config.database) {
    pool = new Pool({
      connectionString: config.database.url,
      max: config.database.maxPoolSize,
    });
    registry.register("database", async () => {
      await pool!.query("SELECT 1");
      return true;
    });
  }

  let consumer: NotificationConsumer | null = null;
  let poller: NotificationOutboxPoller | null = null;
  let kafkaReady = false;
  if (config.kafka && pool) {
    const kafka = new Kafka({
      clientId: config.otel.serviceName,
      brokers: config.kafka.bootstrapServers.split(","),
      logLevel: logLevel.NOTHING,
    });
    consumer = new NotificationConsumer(kafka, pool);
    poller = new NotificationOutboxPoller(pool, kafka.producer());
    registry.register("kafka", () => kafkaReady);
  }

  // 5. Register health plugin
  await app.register(healthPlugin, {
    serviceName: config.otel.serviceName,
    version: config.otel.serviceVersion,
    registry,
  });

  // 6. Backward compat redirects
  app.get("/health", async (_req, reply) => reply.redirect(301, "/liveness"));
  app.get("/ready", async (_req, reply) => reply.redirect(301, "/readiness"));

  // 7. Graceful shutdown
  const shutdown = async () => {
    app.log.info("Notification Service shutting down gracefully...");
    try {
      if (consumer) await consumer.shutdown();
      if (poller) await poller.shutdown();
      if (pool) await pool.end();
    } catch (err) {
      app.log.error(err);
    }
    await app.close();
    await sdk.shutdown();
    process.exit(0);
  };
  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);

  // 8. Start server, then the event pipeline
  try {
    await app.listen({ port: config.server.port, host: config.server.host });
    app.log.info(`Notification Service listening on ${config.server.host}:${config.server.port}`);

    if (poller) await poller.start();
    if (consumer) {
      await consumer.start();
      kafkaReady = true;
      app.log.info("notification-service event pipeline started");
    }
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }
}

start();
