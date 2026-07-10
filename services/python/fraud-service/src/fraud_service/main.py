"""
Fraud Service — Risk & Fraud Detection
=======================================
Core domain service for real-time fraud scoring, velocity checks,
and transaction risk assessment.

Uses payment-platform libs for telemetry, health probes, config, and structured logging.
Consumes `payment-events`, scores, and publishes `fraud-events` via a transactional outbox.
"""

import asyncio
import logging
from contextlib import asynccontextmanager

import asyncpg
from aiokafka import AIOKafkaProducer
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

from fraud_service.consumer import FraudOutboxPoller, run_consumer

logger = logging.getLogger(__name__)

# ─── Config ───────────────────────────────────────────────────────────────
settings = PlatformSettings.load()
settings.validate_mandatory()


def _asyncpg_dsn(url: str) -> str:
    """asyncpg wants a plain postgres DSN (no SQLAlchemy '+asyncpg' driver suffix)."""
    return url.replace("postgresql+asyncpg://", "postgresql://").replace(
        "postgres+asyncpg://", "postgresql://"
    )


# Shared connectivity state — powers honest readiness checks.
_state = {"database": False, "kafka": False}
_runtime: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    tasks: list[asyncio.Task] = []
    pool = None
    producer = None
    poller = None

    if settings.database and settings.database.url:
        pool = await asyncpg.create_pool(
            _asyncpg_dsn(settings.database.url),
            min_size=settings.database.min_idle,
            max_size=settings.database.max_pool_size,
        )
        _state["database"] = True
        _runtime["pool"] = pool

    if settings.kafka and settings.kafka.bootstrap_servers and pool is not None:
        producer = AIOKafkaProducer(
            bootstrap_servers=settings.kafka.bootstrap_servers,
            acks="all",
            enable_idempotence=True,
        )
        await producer.start()
        poller = FraudOutboxPoller(pool, producer)
        tasks.append(asyncio.create_task(poller.run(), name="fraud-outbox-poller"))
        tasks.append(
            asyncio.create_task(
                run_consumer(pool, settings.kafka.bootstrap_servers, _state),
                name="fraud-consumer",
            )
        )
        logger.info("fraud-service event pipeline started")

    try:
        yield
    finally:
        if poller is not None:
            poller.stop()
        for task in tasks:
            task.cancel()
        for task in tasks:
            try:
                await task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001 - shutdown best-effort
                pass
        if producer is not None:
            await producer.stop()
        if pool is not None:
            await pool.close()
        _state["database"] = False
        _state["kafka"] = False
        tracer_provider.shutdown()
        logger.info("fraud-service shut down cleanly")


# ─── App ──────────────────────────────────────────────────────────────────
app = FastAPI(
    title="Fraud Service",
    description="Risk & Fraud Detection for Payment API Platform",
    version=settings.otel.service_version,
    docs_url="/docs" if settings.logging.level == "debug" else None,
    redoc_url="/redoc" if settings.logging.level == "debug" else None,
    lifespan=lifespan,
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
if settings.database:
    registry.register("database", lambda: _state["database"])
if settings.kafka:
    registry.register("kafka", lambda: _state["kafka"])

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


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "fraud_service.main:app",
        host=settings.server.host,
        port=settings.server.port,
        reload=settings.logging.level == "debug",
        log_level=settings.logging.level,
    )
