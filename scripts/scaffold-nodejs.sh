#!/bin/bash
# scaffold-nodejs.sh — Generate new Node.js Fastify service
set -euo pipefail
NAME="${1:-}"
[ -z "$NAME" ] && { echo "Usage: scaffold-nodejs.sh <service-name>"; exit 1; }

SERVICE_DIR="services/nodejs/$NAME"
mkdir -p "$SERVICE_DIR/src" "$SERVICE_DIR/tests" "$SERVICE_DIR/docs/adr"

cat > "$SERVICE_DIR/package.json" <<EOF
{"name":"$NAME","version":"0.1.0","private":true,"main":"dist/main.js",
"scripts":{"dev":"tsx watch src/main.ts","build":"tsc","start":"node dist/main.js","test":"vitest run"},
"dependencies":{"fastify":"^5.0.0","@payment-api/platform-libs":"file:../../libs/nodejs"},
"devDependencies":{"typescript":"^5.5.0","tsx":"^4.15.0","vitest":"^1.6.0","@types/node":"^22.0.0"}}
EOF

cat > "$SERVICE_DIR/tsconfig.json" <<EOF
{"compilerOptions":{"target":"ES2022","module":"commonjs","outDir":"./dist","rootDir":"./src","strict":true,"esModuleInterop":true,"skipLibCheck":true},"include":["src/**/*"]}
EOF

cat > "$SERVICE_DIR/src/main.ts" <<'TSEOF'
import Fastify from "fastify";
import { loadConfig, PlatformConfig } from "@payment-api/platform-libs/config";
import { initTelemetry } from "@payment-api/platform-libs/telemetry";
import { healthPlugin, CachedDependencyRegistry } from "@payment-api/platform-libs/health";

async function start() {
  const config = loadConfig();
  const sdk = initTelemetry(config.otel.serviceName, config.otel.exporterEndpoint, config.otel.serviceVersion);
  const app = Fastify({ logger: { level: config.logging.level } });
  const registry = new CachedDependencyRegistry(5);

  await app.register(healthPlugin, { serviceName: config.otel.serviceName, version: config.otel.serviceVersion, registry });
  app.get("/health", async (_r, reply) => reply.redirect(301, "/liveness"));
  app.get("/ready", async (_r, reply) => reply.redirect(301, "/readiness"));

  process.on("SIGTERM", async () => { await app.close(); await sdk.shutdown(); });
  await app.listen({ port: config.server.port, host: config.server.host });
}
start();
TSEOF

cat > "$SERVICE_DIR/docs/adr/ADR-0001-${NAME}-architecture.md" <<EOF
# ADR-0001: Architecture — $NAME
## Status: Accepted
## Decision: Node.js 22, Fastify + TypeScript, @payment-api/platform-libs
EOF

echo "✅ Node.js service scaffolded: $SERVICE_DIR"
