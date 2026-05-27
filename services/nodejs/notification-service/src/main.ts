/**
 * Notification Service
 * ====================
 * Generic domain service for push, email, SMS, and in-app notifications.
 * Consumes events from Kafka and delivers via appropriate channels.
 */

import Fastify from "fastify";
import cors from "@fastify/cors";

const PORT = parseInt(process.env.PORT || "3001", 10);
const HOST = process.env.HOST || "0.0.0.0";

const app = Fastify({
  logger: {
    level: process.env.LOG_LEVEL || "info",
    transport: process.env.NODE_ENV === "development"
      ? { target: "pino-pretty" }
      : undefined,
  },
});

async function start() {
  // CORS
  await app.register(cors, {
    origin: true,
    credentials: true,
  });

  // ─── Health endpoints ───
  app.get("/health", async () => ({
    status: "UP",
    service: "notification-service",
    version: "0.1.0",
  }));

  app.get("/ready", async () => ({
    status: "READY",
    service: "notification-service",
  }));

  // ─── Start server ───
  try {
    await app.listen({ port: PORT, host: HOST });
    app.log.info(`Notification Service listening on ${HOST}:${PORT}`);
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }
}

start();

export { app };
