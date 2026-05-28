# Phase 11 — Kafka Ecosystem

> **Duration**: 4-6 weeks | **Prerequisites**: Phase 10 (Distributed Systems)
>
> **Goal**: Design, operate, and troubleshoot a production Kafka cluster handling payment events with exactly-once semantics, schema governance, and CDC.
>
> **Why Kafka for the payment platform**: Kafka is the central nervous system. Every domain event — PaymentCompleted, JournalEntryCreated, WalletBalanceUpdated — flows through Kafka. 17 microservices communicate via Kafka topics. Understanding Kafka internals is understanding how the platform communicates.

## Modules

| Module | Topics | Hours |
|--------|--------|:-----:|
| 01 | Architecture, Broker internals, Producer/Consumer deep dive | 15h |
| 02 | Exactly-once, Schema Registry/Avro, Debezium CDC, Kafka Streams | 15h |
| 03 | Operations, KRaft, MirrorMaker 2, Payment Platform Design | 15h |
| Mini Project | Payment Event Pipeline | 15h |

## Resources

- **Book**: "Kafka: The Definitive Guide" (Shapira et al.)
- **Confluent**: developer.confluent.io (free courses)
- **Tool**: AKHQ (Kafka GUI), kcat (CLI producer/consumer)
