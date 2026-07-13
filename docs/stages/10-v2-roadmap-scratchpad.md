# V2 Roadmap — Payment API Platform (Scratchpad)

> Minimal scoping document. Each service follows the Phase-9 patterns already built:
> Debezium CDC outbox, Avro/CloudEvents, inbox, retry+DLQ, minor units, SASL_SSL.

## Services from README roadmap

| Service | Domain | Gist |
|---------|--------|------|
| **refund-service** | New | Reverse ledger entries; consume `payment.succeeded` and issue refunds |
| **transaction-service** | New | Transaction history / ledger query API |
| **dispute-service** | New | Chargeback management; consume `payment.succeeded` |
| **fee-engine** | New | Fee calculation service; configurable rules per merchant |
| **treasury-service** | New | Multi-currency treasury management and FX |
| **reconciliation-service** | New | Compare internal ledger vs bank statements; consume `ledger.entry.committed` |
| **fx-service** | New | Currency conversion rates and spot exchange |
| **audit-service** | New | Consume `platform.audit.action`; storage/compliance |
| **identity-service** | New | Customer KYC / identity verification |
| **merchant-service** | New | Merchant onboarding and configuration |
| **compliance-service** | New | Regulatory reporting and AML checks |
| **bank-integration** | New | ACH/wire transfer integration with external banks |
| **settlement-service** | Existing skeleton | Already wired; needs settlement logic |

## Foundation already built (reuse)

- `docker/Dockerfile.{lang}` + `make scaffold-{lang} NAME=...` — service scaffolding
- `libs/{java,go,python,nodejs}` — health probes, telemetry, config per language
- `docker-compose.yml` — infra (Postgres, Kafka, Redis, OTel, Debezium, Registry)
- `scripts/register-connectors.sh` — add a connector for each new DB
- `shared/events/schemas/*.avsc` — schema authoring pattern (P0)
- `consumer_inbox` — dedup + retry pattern (P2)
- `outbox` table + Debezium connector — producer pattern (P1)

## Open before starting any service
- Which services need to exist (MVP subset from the full list)?
- Is the serial chain preserved for refund/dispute (they interact with payment)?
- Settlement service: what event does it consume (`payment.succeeded` only, or more)?
