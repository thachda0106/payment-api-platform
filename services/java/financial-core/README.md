# Financial Core Service

**Language**: Java 21 + Spring Boot 3.3
**Domain**: Core — Ledger + Wallet
**Database**: `financial_core_db`

## Overview

The Financial Core service is the system of record for all monetary transactions. It implements:
- Double-entry accounting via `journal_entries` and `journal_lines`
- Wallet balance projection (updated atomically in the same transaction as journal entries)
- Balance holds for pending transactions
- Optimistic concurrency control via version columns

## Architecture

```
Controller (REST) → Service (DDD) → Repository (JPA) → PostgreSQL
                                     ↓
                                  Kafka Producer (Outbox → CDC)
```

## Quick Start

### Prerequisites
- Java 21
- Maven 3.9+

### Run Locally
```bash
cd services/java/financial-core
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### With Docker
```bash
docker build -f docker/Dockerfile.java -t payment-api/financial-core:latest services/java/financial-core
docker run -p 8080:8080 payment-api/financial-core:latest
```

### With Docker Compose
```bash
docker-compose --profile services up -d financial-core
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness check |
| GET | `/ready` | Readiness check |
| GET | `/actuator/prometheus` | Prometheus metrics |

## Domain Events Produced

- `JournalEntryCreated` → `financial-core.journal.entries`
- `WalletBalanceUpdated` → `financial-core.wallet.events`

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/financial_core_db` | Database URL |
| `DATASOURCE_USERNAME` | `payment` | Database user |
| `DATASOURCE_PASSWORD` | `payment` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `SERVER_PORT` | `8080` | HTTP port |
