# Notification Service

**Language**: Node.js 22 + Fastify + TypeScript
**Domain**: Generic — Notification Delivery
**Database**: `notification_db`

## Overview

The Notification Service delivers messages to users via:
- Push notifications (FCM Android, APNs iOS)
- Email (SMTP)
- SMS
- In-app notifications

It consumes events from Kafka and routes them to the appropriate delivery channel.

## Architecture

```
Kafka Consumer → Fastify Server → Delivery Channels
                                  ├── Push (FCM/APNs)
                                  ├── Email (SMTP)
                                  └── SMS
```

## Quick Start

### Prerequisites
- Node.js 22
- npm

### Run Locally
```bash
cd services/nodejs/notification-service
npm install
npm run dev
```

### With Docker
```bash
docker build -f docker/Dockerfile.nodejs -t payment-api/notification-service:latest services/nodejs/notification-service
docker run -p 3001:3001 payment-api/notification-service:latest
```

### With Docker Compose
```bash
docker-compose --profile services up -d notification-service
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness check |
| GET | `/ready` | Readiness check |

## Domain Events Consumed

- `payment.completed` → payment confirmation notification
- `payment.failed` → failure notification
- `refund.completed` → refund notification

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3001` | HTTP port |
| `DATABASE_URL` | `postgresql://...` | Database URL |
| `KAFKA_BROKERS` | `localhost:9092` | Kafka brokers |
| `SMTP_HOST` | `localhost` | Email server |
