# Settlement Service

**Language**: Go 1.22+ + Chi
**Domain**: Supporting — EOD Settlement
**Database**: `settlement_db`

## Overview

The Settlement Service handles End-of-Day merchant settlement batch processing:
- Aggregates merchant payment volumes
- Calculates net settlement amounts
- Generates settlement files for bank payout
- Manages settlement batch lifecycle

## Architecture

```
Chi HTTP Server → Service Layer → PostgreSQL (sqlc)
                                 → Kafka Consumer (payment events)
                                 → Kafka Producer (settlement events)
```

## Quick Start

### Prerequisites
- Go 1.22+
- `go mod` configured

### Run Locally
```bash
cd services/go/settlement-service
go run ./cmd/server
```

### With Docker
```bash
docker build -f docker/Dockerfile.go -t payment-api/settlement-service:latest services/go/settlement-service
docker run -p 8088:8088 payment-api/settlement-service:latest
```

### With Docker Compose
```bash
docker-compose --profile services up -d settlement-service
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness check (service name, version, uptime) |
| GET | `/ready` | Readiness check |

## Domain Events Consumed

- `payment.completed` → accumulates merchant volumes
- `refund.completed` → adjusts settlement amounts

## Domain Events Produced

- `settlement.batch.started` → `settlement.events`
- `settlement.batch.completed` → `settlement.events`

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8088` | HTTP port |
| `DATABASE_URL` | `postgresql://...` | Database URL |
| `KAFKA_BROKERS` | `localhost:9092` | Kafka brokers |
| `ENVIRONMENT` | `development` | Deployment environment |
