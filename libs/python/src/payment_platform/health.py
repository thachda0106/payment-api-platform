"""
Kubernetes-style probe endpoints with cached dependency registry.

Provides FastAPI routers for:
    - GET /liveness   → always 200 (no I/O)
    - GET /readiness  → 200 if all deps OK, 503 otherwise (cached, TTL 5s)
    - GET /startup    → 503 until first successful readiness, then 200 permanently

Usage:
    registry = CachedDependencyRegistry(ttl_seconds=5)
    registry.register("database", DatabaseChecker(db_url))
    app.include_router(create_liveness_router("my-service", "0.1.0"))
    app.include_router(create_readiness_router("my-service", "0.1.0", registry))
    app.include_router(create_startup_router("my-service", "0.1.0", registry))
"""

import time
import threading
from datetime import datetime, timezone
from enum import Enum
from typing import Callable, Dict, Optional
from fastapi import APIRouter, Response


class DependencyStatus(str, Enum):
    OK = "ok"
    DOWN = "down"
    UNUSED = "unused"

    def is_healthy(self) -> bool:
        return self in (DependencyStatus.OK, DependencyStatus.UNUSED)


class CheckResult:
    """Result of a single dependency check."""
    def __init__(self, status: DependencyStatus, latency_ms: float = 0):
        self.status = status
        self.latency_ms = latency_ms
        self.last_checked = datetime.now(timezone.utc)

    def to_dict(self) -> dict:
        return {
            "status": self.status.value,
            "latencyMs": round(self.latency_ms, 2),
            "lastChecked": self.last_checked.isoformat(),
        }


class CachedDependencyRegistry:
    """Thread-safe registry of dependency checks with TTL-based caching."""

    def __init__(self, ttl_seconds: int = 5):
        self._ttl = ttl_seconds
        self._checks: Dict[str, Callable[[], bool]] = {}
        self._cache: Dict[str, tuple[CheckResult, float]] = {}
        self._lock = threading.RLock()

    def register(self, name: str, check_fn: Callable[[], bool]) -> None:
        """Register a named dependency check."""
        with self._lock:
            self._checks[name] = check_fn

    def get_statuses(self) -> Dict[str, CheckResult]:
        """Return cached status of all checks. Performs fresh checks after TTL expires."""
        with self._lock:
            now = time.time()
            results = {}
            for name, check_fn in self._checks.items():
                # Use cache if fresh
                if name in self._cache:
                    cached_result, cached_time = self._cache[name]
                    if now - cached_time < self._ttl:
                        results[name] = cached_result
                        continue

                # Perform fresh check
                start = time.perf_counter()
                try:
                    healthy = check_fn()
                except Exception:
                    healthy = False
                latency_ms = (time.perf_counter() - start) * 1000

                status = DependencyStatus.OK if healthy else DependencyStatus.DOWN
                result = CheckResult(status, latency_ms)
                self._cache[name] = (result, now)
                results[name] = result

            return results

    def all_healthy(self) -> bool:
        """Return True if all registered checks are healthy."""
        return all(r.status.is_healthy() for r in self.get_statuses().values())

    def invalidate(self) -> None:
        """Force recheck of all dependencies on next probe."""
        with self._lock:
            self._cache.clear()


def _start_time() -> float:
    import os
    try:
        import psutil
        return psutil.Process(os.getpid()).create_time()
    except ImportError:
        return time.time()


_start = _start_time()


def _probe_response(status: str, service: str, version: str, checks: Optional[Dict] = None) -> dict:
    return {
        "status": status,
        "service": service,
        "version": version,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "uptime": round(time.time() - _start, 3),
        "checks": checks or {},
    }


def create_liveness_router(service_name: str, version: str) -> APIRouter:
    """GET /liveness — always 200. No I/O, no dependency checks."""
    router = APIRouter(tags=["health"])

    @router.get("/liveness")
    async def liveness():
        return _probe_response("ok", service_name, version)

    return router


def create_readiness_router(service_name: str, version: str, registry: CachedDependencyRegistry) -> APIRouter:
    """GET /readiness — returns 200 if all deps OK, 503 otherwise. Cached checks."""
    router = APIRouter(tags=["health"])

    @router.get("/readiness")
    async def readiness(response: Response):
        statuses = registry.get_statuses()
        all_ok = all(s.status.is_healthy() for s in statuses.values())

        status_text = "ok" if all_ok else "not_ready"
        checks_dict = {k: v.to_dict() for k, v in statuses.items()}

        if not all_ok:
            response.status_code = 503

        return _probe_response(status_text, service_name, version, checks_dict)

    return router


def create_startup_router(service_name: str, version: str, registry: CachedDependencyRegistry) -> APIRouter:
    """GET /startup — returns 503 until first successful readiness, then 200 permanently."""
    router = APIRouter(tags=["health"])
    started = threading.Event()

    @router.get("/startup")
    async def startup(response: Response):
        statuses = registry.get_statuses()
        all_ok = all(s.status.is_healthy() for s in statuses.values())

        if all_ok:
            started.set()

        status_text = "ok" if started.is_set() else "not_ready"
        checks_dict = {k: v.to_dict() for k, v in statuses.items()}

        if not started.is_set():
            response.status_code = 503

        return _probe_response(status_text, service_name, version, checks_dict)

    return router
