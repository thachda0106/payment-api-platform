# Phase 10 — Distributed Systems Theory

> **Duration**: 6-8 weeks | **Prerequisites**: Phase 9 (PostgreSQL Internals)
>
> **Goal**: Design distributed architectures that survive network partitions, node failures, and concurrent operations. Every design decision justified by theory.
>
> **Why this matters**: A payment platform IS a distributed system. Every cross-service call (Payment→Fraud→Ledger→Notification) is a distributed system interaction. The failure modes of distributed systems ARE the failure modes of your payment platform.

## Modules

| Module | Topics | Hours |
|--------|--------|:-----:|
| 01 | CAP, Consistency Models, Consensus (Paxos, Raft), Leader Election | 15h |
| 02 | Replication, Sharding, Distributed Transactions (2PC/3PC), Sagas | 15h |
| 03 | CQRS, Event Sourcing, Outbox/Inbox, Idempotency, Retry | 15h |
| 04 | Circuit Breaker, Bulkhead, Backpressure, Distributed Locks, Chaos Engineering | 15h |
| Mini Project | Distributed Saga Orchestrator | 15h |

## Resources

- **Book**: "Designing Data-Intensive Applications" (Kleppmann) — Chapters 5, 7, 8, 9, 11
- **Paper**: "In Search of an Understandable Consensus Algorithm" (Ongaro) — the Raft paper
- **Blog**: aphyr.com (Jepsen) — distributed systems correctness
- **Course**: MIT 6.824 Distributed Systems (free on YouTube)
