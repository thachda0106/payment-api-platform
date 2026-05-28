# Mini Project — Payment Event Pipeline

## Goal

Build the complete payment event pipeline: Payment Service → Outbox → CDC Relay → Kafka → Multiple Consumers (Notification, Audit). Demonstrates: outbox pattern, inbox deduplication, at-least-once delivery, and consumer groups.

## What You Will Build

- **Outbox Relay**: Simulates Debezium CDC — writes to outbox, relays to Kafka topics
- **Payment Service**: Writes to outbox in same "transaction" (simulated)
- **Notification Consumer**: Consumes PaymentCompleted events, sends notifications, inbox dedup
- **Audit Consumer**: Consumes JournalEntryCreated events, writes immutable audit log

## Architecture

```
Payment Service → Outbox (in-memory list) → Relay → Kafka Topics
                                                          │
                                    ┌─────────────────────┼─────────────────────┐
                                    ▼                     ▼                     ▼
                            Notification Consumer  Audit Consumer      Search Indexer
```

## Run

```bash
javac PaymentEventPipeline.java && java PaymentEventPipeline
```

## Acceptance Criteria

1. 10 payments processed → 10 notifications delivered
2. Duplicate event (same eventId) → ignored by inbox deduplication
3. Consumer lag = 0 after processing completes
4. Consumers run concurrently (Notification + Audit in separate threads)
