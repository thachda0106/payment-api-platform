# Fraud Service

**Language**: Python 3.12 + FastAPI
**Domain**: Core — Risk & Fraud Detection
**Database**: `fraud_db`

## Overview

The Fraud Service provides real-time risk assessment for payment transactions:
- Fraud scoring (ML-based)
- Velocity checks (transactions per time window)
- Transaction limit enforcement
- Account freeze/unfreeze management

## Architecture

```
FastAPI (REST) → Service Layer → SQLAlchemy (async) → PostgreSQL
                                → Redis (velocity counters)
                                → Kafka Consumer (events)
```

## Quick Start

### Prerequisites
- Python 3.12
- pip

### Run Locally
```bash
cd services/python/fraud-service
pip install -r requirements.txt
pip install -e .
uvicorn fraud_service.main:app --reload --port 8000
```

### With Docker
```bash
docker build -f docker/Dockerfile.python -t payment-api/fraud-service:latest services/python/fraud-service
docker run -p 8000:8000 payment-api/fraud-service:latest
```

### With Docker Compose
```bash
docker-compose --profile services up -d fraud-service
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness check |
| GET | `/ready` | Readiness check |
| GET | `/docs` | Swagger UI (debug mode) |

## Domain Events Consumed

- `payment.created` → triggers fraud scoring
- `payment.succeeded` → updates risk profiles

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `postgresql+asyncpg://...` | Database URL |
| `REDIS_URL` | `redis://localhost:6379` | Redis URL |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `FRAUD_SCORE_THRESHOLD` | `0.7` | Score above which to block |
