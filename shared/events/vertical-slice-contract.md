# Vertical-Slice Event Contract (v1)

> Canonical JSON contract for the implemented payment vertical slice
> (`payment → fraud → financial-core → notification`).
> Scope: this simplified slice only. The Phase-9 CloudEvents/Avro governance
> (`docs/cross-cutting/events/`) is a separate, future alignment initiative.

## Envelope (all events)

```json
{
  "v": 1,
  "eventId": "1b4e28ba-2fa1-11d2-883f-0016d3cca427",
  "type": "PaymentCreated",
  "paymentId": "9f8c7b6a-1234-4c5d-8e9f-0011223344556",
  "amount": "99.99",
  "currency": "USD",
  "customerId": "c1",
  "merchantId": "m1",
  "timestamp": "2026-07-09T12:34:56.789Z"
}
```

## Field constraints

| Field | Type | Constraint |
|-------|------|-----------|
| `v` | number | schema version, currently `1` |
| `eventId` | string | UUID — dedup key (`processed_events`) |
| `type` | string | one of `PaymentCreated`, `PaymentApproved`, `PaymentRejected`, `LedgerEntryCreated`, `NotificationSent` |
| `paymentId` | string | UUID — Kafka partition key (ordering) |
| `amount` | string | decimal, `> 0`; string form avoids float drift (maps to Java `BigDecimal`, Python `Decimal`) |
| `currency` | string | `^[A-Z]{3}$` (ISO 4217) |
| `customerId` | string | non-blank, ≤ 64 chars |
| `merchantId` | string | non-blank, ≤ 64 chars |
| `timestamp` | string | RFC 3339 |

## Type-specific additions

`fraud-events` (`PaymentApproved` / `PaymentRejected`) additionally carry:

| Field | Type | Constraint |
|-------|------|-----------|
| `score` | number | `0..100` |
| `decision` | string | one of `APPROVED`, `REVIEW`, `REJECTED` |
| `reason` | string | ≤ 255 chars |

## Rules

- **eventId ≠ paymentId.** `eventId` dedups; `paymentId` (as `aggregateId`) orders.
- Producers write the event to their local `*_outbox` table **in the same DB transaction**
  as their state change; a poller publishes to Kafka (at-least-once).
- Consumers **validate** these fields on ingest. Invalid/unparseable messages go to
  `<topic>-dlq` — never silently swallowed.
- Delivery is **at-least-once**; consumers dedup via `processed_events(event_id, consumer_group)`.

## Topic map (this slice)

| Topic | Producer | Consumer | Key |
|-------|----------|----------|-----|
| `payment-events` | payment-service | fraud-service | `paymentId` |
| `fraud-events` | fraud-service | financial-core | `paymentId` |
| `ledger-events` | financial-core | notification-service | `paymentId` |
| `notification-events` | notification-service | (none yet) | `paymentId` |
| `*-dlq` | any consumer | ops/replay | original key |
