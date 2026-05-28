# Module 01 — Kafka Architecture, Producers & Consumers

## 1.1 Kafka Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     KAFKA CLUSTER                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
│  │ Broker 1 │  │ Broker 2 │  │ Broker 3 │                   │
│  │ P0 (L)   │  │ P0 (F)   │  │ P0 (F)   │                   │
│  │ P1 (F)   │  │ P1 (L)   │  │ P1 (F)   │                   │
│  │ P2 (F)   │  │ P2 (F)   │  │ P2 (L)   │                   │
│  └──────────┘  └──────────┘  └──────────┘                   │
└──────────────────────────────────────────────────────────────┘
P0,P1,P2 = Partitions of topic "payments.events"
(L) = Leader  (F) = Follower
```

**Topic → Partitions → Segments**:
- **Topic**: Logical category (`payments.payment.succeeded`)
- **Partition**: Physical append-only log file. Ordered, immutable. Each record has an **offset** (monotonically increasing within the partition).
- **Segment**: On-disk file storing a range of offsets. New segment created when size/time limit reached.

**Controller**: One broker elected as controller (via KRaft). Manages partition leader election and broker membership.

**ISR (In-Sync Replicas)**: Replicas fully caught up with leader. `min.insync.replicas = 2` means `acks=all` waits for leader + 1 follower. This is your durability guarantee.

## 1.2 Producer Internals

```
Producer.send(record)
    │
    ▼  Serializer → Partitioner → RecordAccumulator (batching) → Sender → Broker
```

### Key Producer Configs for Payments

| Config | Value | Why |
|--------|:-----:|-----|
| `acks` | `all` | Wait for all ISR. Payment events MUST be durable. |
| `enable.idempotence` | `true` | Producer retries won't cause duplicates. |
| `max.in.flight.requests.per.connection` | `5` | Pipelining for throughput. Idempotent producer preserves ordering with up to 5 in-flight. |
| `linger.ms` | `5-10` | Small batch delay for better compression/throughput. |
| `compression.type` | `lz4` or `zstd` | Best throughput/compression trade-off. |
| `retries` | `MAX_INT` | With idempotence, retry indefinitely safely. |

## 1.3 Consumer Internals

### Consumer Groups

Consumers with same `group.id` divide partitions among themselves. Each partition consumed by exactly ONE consumer in the group → ordered within partition, parallel across partitions.

### Partition Assignment

- **Range** (default): Contiguous ranges. Can be unbalanced.
- **Cooperative Sticky** (recommended): Minimizes partition movement during rebalance. Incremental reassignment.

### Offset Management

Consumers track position via offsets (last processed record). Committed to `__consumer_offsets` topic.

- **Auto-commit**: `enable.auto.commit=true` → commits every N ms. Risk: messages processed but not committed → crash → reprocess.
- **Manual commit**: Application calls `commitSync()` or `commitAsync()` AFTER processing. More control.

### Rebalance — The Stop-the-World Event

When a consumer joins/leaves the group, ALL consumers stop processing while partitions are reassigned. Minimize rebalances:
- `session.timeout.ms = 45000` (don't let transient GC pauses trigger rebalance)
- `max.poll.interval.ms = 300000` (allow long processing)
- Use Cooperative Sticky (incremental rebalance — most partitions keep consuming)

### Lag Monitoring

Consumer lag = producer offset - consumer offset. Growing lag = consumer can't keep up. Alert at 5K (warning), 50K (critical).

## 1.4 Partitioning Strategy

### Key Selection

- `payment_id` → hash → partition: All events for same payment go to same partition → ORDERED within a payment lifecycle.
- `wallet_id` → hash → partition: Balance updates for same wallet are ordered.
- Partition count ≥ max consumers in group. If you expect 12 consumers, you need ≥12 partitions.

### Payment Platform Topic Design

| Topic | Key | Partitions | Retention | Consumers |
|-------|:---|:----------:|:---------:|-----------|
| `payments.payment.succeeded` | payment_id | 12 | 7d | Wallet, Notification, Settlement, Search |
| `wallets.balance.updated` | wallet_id | 12 | 7d | Search, Analytics, Notification |
| `refunds.refund.completed` | refund_id | 6 | 7d | Wallet, Settlement, Notification |
| `ledger.entry.committed` | entry_id | 12 | 30d | Audit, Reconciliation, Analytics |

## 1.5 Exercises

### Ex 1.1 — Producer Benchmark
Write a producer with varying: acks (0/1/all), linger.ms (0/1/5/10/100), compression (none/gzip/snappy/lz4). Plot throughput vs latency.

### Ex 1.2 — Consumer Rebalance
Create a 3-consumer group. Add 4th consumer. Observe rebalance via consumer group describe. Kill one. Observe rebalance. Measure stop-the-world time.

### Ex 1.3 — Partition Key Design
Given payment events requiring strict per-account ordering, design the partition key strategy. Explain why `account_id` hash works and what trade-offs it introduces (hot partitions?).

## 1.6 Self-Assessment

- [ ] Can explain the path of a producer record from send() to broker acknowledgment
- [ ] Understand why `enable.idempotence=true` + `acks=all` is required for payment events
- [ ] Know the difference between Range and Cooperative Sticky assignment
- [ ] Can diagnose consumer lag from command-line tools
- [ ] Can design a topic with appropriate partition count, replication factor, and retention
