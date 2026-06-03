"""
Fraud Service — Risk & Fraud Detection
=======================================
Core domain service for real-time fraud scoring, velocity checks,
and transaction risk assessment.

Uses payment-platform libs for telemetry, health probes, config, and structured logging.
"""

from fastapi import FastAPI
from fastapi.responses import RedirectResponse

from payment_platform.config import PlatformSettings
from payment_platform.telemetry import setup_telemetry
from payment_platform.health import (
    CachedDependencyRegistry,
    create_liveness_router,
    create_readiness_router,
    create_startup_router,
)

# ─── Config ───────────────────────────────────────────────────────────────
settings = PlatformSettings.load()
settings.validate_mandatory()

# ─── App ──────────────────────────────────────────────────────────────────
app = FastAPI(
    title="Fraud Service",
    description="Risk & Fraud Detection for Payment API Platform",
    version=settings.otel.service_version,
    docs_url="/docs" if settings.logging.level == "debug" else None,
    redoc_url="/redoc" if settings.logging.level == "debug" else None,
)

# ─── Telemetry ────────────────────────────────────────────────────────────
tracer_provider = setup_telemetry(
    app=app,
    service_name=settings.otel.service_name,
    service_version=settings.otel.service_version,
    exporter_endpoint=settings.otel.exporter_endpoint,
)

# ─── Health Probes ────────────────────────────────────────────────────────
registry = CachedDependencyRegistry(ttl_seconds=5)

# Register checks if modules are configured
if settings.database:
    import sqlalchemy
    # Database check will be registered when engine is created (Phase 7)
    pass

if settings.redis:
    # Redis check will be registered when client is created (Phase 7)
    pass

app.include_router(create_liveness_router(settings.otel.service_name, settings.otel.service_version))
app.include_router(create_readiness_router(settings.otel.service_name, settings.otel.service_version, registry))
app.include_router(create_startup_router(settings.otel.service_name, settings.otel.service_version, registry))

# ─── Backward Compat ──────────────────────────────────────────────────────
@app.get("/health", status_code=301)
async def health_redirect():
    return RedirectResponse("/liveness")

@app.get("/ready", status_code=301)
async def ready_redirect():
    return RedirectResponse("/readiness")


# ─── Shutdown ─────────────────────────────────────────────────────────────
import signal, sys, asyncio

def _shutdown():
    print("Shutting down Fraud Service...")
    tracer_provider.shutdown()

signal.signal(signal.SIGTERM, lambda s, f: _shutdown())
signal.signal(signal.SIGINT, lambda s, f: _shutdown())


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "fraud_service.main:app",
        host=settings.server.host,
        port=settings.server.port,
        reload=settings.logging.level == "debug",
        log_level=settings.logging.level,
    )
