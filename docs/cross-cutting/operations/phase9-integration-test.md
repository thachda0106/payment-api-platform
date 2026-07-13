# Phase-9 Integration Test Specification

> Manual/CI verification for the Debezium + Avro + inbox pipeline (P1/P2 gates).
> Unit tests cannot cover CDC — these steps require the integrated stack.

## Prerequisites

```bash
docker compose up -d                       # infra + Debezium Connect + Schema Registry
bash scripts/register-schemas.sh           # design schemas (Debezium-owned subjects skipped)
bash scripts/register-connectors.sh        # 4 Debezium outbox connectors
docker compose --profile services up -d    # the 5 services
```

Health gates: `postgres` shows `wal_level=logical`; `debezium-connect` `/connectors` returns the
4 connectors in `RUNNING` state; Schema Registry `/subjects` lists the design subjects.

## T1 — Producer path (P1)

1. `POST /v1/payments` with an `Idempotency-Key`.
2. Assert a row appears in `payment_db.outbox`.
3. Assert an **Avro** message on `payments.payment.created`:
   ```
   docker exec payment-debezium-connect kafka-avro-console-consumer \
     --bootstrap-server kafka:9092 --property schema.registry.url=http://schema-registry:8081 \
     --topic payments.payment.created --from-beginning --max-messages 1
   ```
   Expect CloudEvents structure with `data.amount` in **minor units**.

## T2 — Full serial chain (P1 + P2)

For one payment, poll until all are true (timeout 120s):
- `fraud_db.fraud_scores` has 1 row; `fraud_db.consumer_inbox` row `COMPLETED`.
- `fraud_db.outbox` has a `payments.payment.succeeded` row → Avro on that topic.
- `financial_core_db.journal_entries` has 3 rows (sum-zero); `consumer_inbox` `COMPLETED`.
- `financial_core_db.outbox` has a `ledger.entry.committed` row → Avro on that topic.
- `notification_db.notifications` has 1 row; `consumer_inbox` `COMPLETED`.

## T3 — Idempotency / redelivery (P2 inbox)

1. Re-produce a duplicate `payments.payment.succeeded` (same CloudEvents `id`) via console producer.
2. Assert `financial_core_db.journal_entries` count is unchanged (inbox dedup + ledger guard).

## T4 — Retry + DLQ (P2 inbox retry scheduler)

1. Produce a malformed Avro message to `payments.payment.succeeded` (or a valid event whose
   processing will fail, e.g. temporarily break the DB).
2. Assert the `consumer_inbox` row goes `FAILED`, `retry_count` increments on each scheduler tick
   with exponential backoff, and after 5 attempts the event is produced to `payments.dlq` and the
   inbox row becomes `DLQ`.

## T5 — Crash safety

1. Kill `financial-core` between the inbox `PENDING` claim and processing (offset not committed).
2. On restart, assert the event is reprocessed (inbox `PENDING` → `COMPLETED`) with exactly one
   ledger posting (no duplicate journal entries).

## Notes

- The wire schema for produced topics is **Debezium-inferred** (nested union tag `payload.data`).
  Consumers deserialize via Confluent Avro (Java `KafkaAvroDeserializer`, Python fastavro +
  registry, Node `@kafkajs/confluent-schema-registry`).
- DLQ topics: `payments.dlq` (fraud/financial consume payments/…succeeded), `ledger.dlq`
  (notification consumes ledger.entry.committed), `notifications.dlq` (future).
