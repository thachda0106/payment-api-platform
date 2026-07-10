import { describe, it, expect, beforeAll, afterAll } from "vitest";
import Fastify, { FastifyInstance } from "fastify";
import { healthPlugin, CachedDependencyRegistry } from "@payment-api/platform-libs/health";

describe("Health endpoints", () => {
  let app: FastifyInstance;

  beforeAll(async () => {
    app = Fastify();
    await app.register(healthPlugin, {
      serviceName: "notification-service",
      version: "0.1.0",
      registry: new CachedDependencyRegistry(5),
    });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  it("GET /liveness returns ok", async () => {
    const res = await app.inject({ method: "GET", url: "/liveness" });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.status).toBe("ok");
    expect(body.service).toBe("notification-service");
  });

  it("GET /readiness returns ok when no dependencies are registered", async () => {
    const res = await app.inject({ method: "GET", url: "/readiness" });
    expect(res.statusCode).toBe(200);
    expect(res.json().status).toBe("ok");
  });
});
