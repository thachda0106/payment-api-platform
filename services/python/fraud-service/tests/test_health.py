"""Tests for health endpoints.

Builds a minimal app with the platform health routers so the test does not pull
the full service bootstrap (Kafka/DB clients). Requires the dev environment
(`pip install -r requirements-dev.txt`): fastapi, payment_platform, pytest-asyncio, httpx.
"""

import pytest
from fastapi import FastAPI
from httpx import AsyncClient, ASGITransport

from payment_platform.health import (
    CachedDependencyRegistry,
    create_liveness_router,
    create_readiness_router,
)

app = FastAPI()
_registry = CachedDependencyRegistry(ttl_seconds=5)
app.include_router(create_liveness_router("fraud-service", "0.1.0"))
app.include_router(create_readiness_router("fraud-service", "0.1.0", _registry))


@pytest.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        yield ac


@pytest.mark.asyncio
async def test_liveness_returns_ok(client: AsyncClient):
    response = await client.get("/liveness")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["service"] == "fraud-service"


@pytest.mark.asyncio
async def test_readiness_ok_when_no_dependencies(client: AsyncClient):
    response = await client.get("/readiness")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
