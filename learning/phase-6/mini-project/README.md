# Mini Project — Webhook Delivery Service

## Goal

Build an event-driven webhook delivery service that receives events, enqueues them, and delivers to configured merchant webhook URLs with exponential backoff retry, rate limiting, and HMAC signatures.

## What You Will Build

- **Event Queue**: In-memory queue with retry scheduling
- **Rate Limiter**: Max 10 req/s per merchant URL
- **Exponential Backoff**: 1s → 2s → 4s → 8s → 16s, max 5 retries
- **HMAC Signatures**: `X-Webhook-Signature` header for merchant verification
- **Idempotency**: Each event has a unique `eventId` — merchant deduplicates
- **Observability**: Events for enqueued, delivered, retried, failed_permanently
- **Health endpoint**: `GET /health` returns queue size + processing status

## Architecture

```
POST /webhooks/deliver → Queue → [Rate Limiter] → POST to merchant URL
                                  ↓ failure
                               Retry (backoff)
                                  ↓ max retries
                               Dead Letter (failed_permanently)
```

## Run

```bash
npx tsx webhook_service.ts
# Then: curl -X POST http://localhost:3001/webhooks/deliver -H "Content-Type: application/json" -d '{"eventId":"evt-1","eventType":"payment.completed","payload":{"amount":100000}}'
```

## Acceptance Criteria

1. Enqueued events are delivered to merchant URL with correct headers
2. Rate limiter enforces max throughput per URL
3. Failed deliveries retry with exponential backoff
4. Permanent failures (max retries exceeded) emit `failed_permanently` event
5. `GET /health` returns current queue size and processing status
