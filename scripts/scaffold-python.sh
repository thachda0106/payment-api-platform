#!/bin/bash
# scaffold-python.sh — Generate new Python FastAPI service
set -euo pipefail
NAME="${1:-}"
[ -z "$NAME" ] && { echo "Usage: scaffold-python.sh <service-name>"; exit 1; }

SERVICE_DIR="services/python/$NAME"
mkdir -p "$SERVICE_DIR/src/${NAME//-/_}" "$SERVICE_DIR/tests" "$SERVICE_DIR/docs/adr"

cat > "$SERVICE_DIR/pyproject.toml" <<EOF
[project]
name = "${NAME//-/_}"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = ["fastapi>=0.111.0", "uvicorn[standard]>=0.29.0", "payment-platform"]
EOF

cat > "$SERVICE_DIR/src/${NAME//-/_}/main.py" <<'PYEOF'
from fastapi import FastAPI
from fastapi.responses import RedirectResponse
from payment_platform.config import PlatformSettings
from payment_platform.telemetry import setup_telemetry
from payment_platform.health import CachedDependencyRegistry, create_liveness_router, create_readiness_router, create_startup_router

settings = PlatformSettings.load()
settings.validate_mandatory()

app = FastAPI(title="${NAME}", version=settings.otel.service_version)

tracer = setup_telemetry(app, settings.otel.service_name, settings.otel.service_version, settings.otel.exporter_endpoint)

registry = CachedDependencyRegistry(ttl_seconds=5)
app.include_router(create_liveness_router(settings.otel.service_name, settings.otel.service_version))
app.include_router(create_readiness_router(settings.otel.service_name, settings.otel.service_version, registry))
app.include_router(create_startup_router(settings.otel.service_name, settings.otel.service_version, registry))

@app.get("/health", status_code=301)
async def h(): return RedirectResponse("/liveness")
@app.get("/ready", status_code=301)
async def r(): return RedirectResponse("/readiness")
PYEOF

cat > "$SERVICE_DIR/docs/adr/ADR-0001-${NAME}-architecture.md" <<EOF
# ADR-0001: Architecture — $NAME
## Status: Accepted
## Decision: Python 3.12, FastAPI, platform-libs
EOF

echo "✅ Python service scaffolded: $SERVICE_DIR"
