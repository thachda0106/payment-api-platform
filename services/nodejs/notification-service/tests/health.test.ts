import { describe, it, expect } from "vitest";
import { app } from "../src/main";

describe("Health endpoints", () => {
  it("GET /health returns UP", async () => {
    const response = await app.inject({
      method: "GET",
      url: "/health",
    });

    expect(response.statusCode).toBe(200);
    const body = response.json();
    expect(body.status).toBe("UP");
    expect(body.service).toBe("notification-service");
  });

  it("GET /ready returns READY", async () => {
    const response = await app.inject({
      method: "GET",
      url: "/ready",
    });

    expect(response.statusCode).toBe(200);
    const body = response.json();
    expect(body.status).toBe("READY");
  });
});
