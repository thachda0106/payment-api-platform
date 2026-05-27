"""
Fraud Service — Risk & Fraud Detection
=======================================
Core domain service for real-time fraud scoring, velocity checks,
and transaction risk assessment.
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from fraud_service.config import settings

app = FastAPI(
    title="Fraud Service",
    description="Risk & Fraud Detection for Payment API Platform",
    version="0.1.0",
    docs_url="/docs" if settings.debug else None,
    redoc_url="/redoc" if settings.debug else None,
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health():
    """Liveness check."""
    return {
        "status": "UP",
        "service": "fraud-service",
        "version": "0.1.0",
    }


@app.get("/ready")
async def ready():
    """Readiness check."""
    return {
        "status": "READY",
        "service": "fraud-service",
    }
