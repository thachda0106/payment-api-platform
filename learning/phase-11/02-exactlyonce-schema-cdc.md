# Module 02 — Exactly-Once, Schema Registry, CDC & Streams

## 2.1 Exactly-Once Semantics

### At-Least-Once (Default, Safest)

Producer: `acks=all` + manual commit after processing. On crash: may reprocess → duplicates. Consumer must be idempotent (inbox pattern).

### Exactly-Once (Transactional API)

Producer: `initTransactions()` → `beginTransaction()` → send → `commitTransaction()`. Consumer: `isolation.level=read_committed` — only sees committed transactional messages.

**How it works**: Producer sends "commit marker" after data. Consumer buffers uncommitted messages, delivers only after seeing commit marker. Adds latency and complexity.

### Payment Platform Approach

Use at-least-once with idempotent consumers. EOS transactions add latency that's rarely justified for event consumers that ALREADY need idempotency (inbox dedup by eventId). Keep it simple.

## 2.2 Schema Registry & Avro

### Avro

Binary serialization. Records contain schema ID (4 bytes) + data. No field names in wire format → compact. Schema stored in registry.

### Compatibility Modes

| Mode | Rule | Safe to Upgrade |
|------|------|:---------------:|
| **BACKWARD** | New schema can read old data | Consumers first |
| **FORWARD** | Old schema can read new data | Producers first |
| **FULL** | Both BACKWARD + FORWARD | Either first |
| **NONE** | No checks | Unsafe |

```avsc
// v1
{"type":"record","name":"PaymentSucceeded","fields":[
  {"name":"payment_id","type":"string"},
  {"name":"amount","type":"long"}
]}

// v2 — add field with DEFAULT (BACKWARD compatible)
{"name":"fee_amount","type":"long","default":0}
```

## 2.3 Debezium CDC + Outbox Pattern

### Pipeline

```
PostgreSQL outbox_events → Debezium (pgoutput logical decoding) → EventRouter SMT → Kafka topic
```

**EventRouter SMT** transforms Debezium change event → clean event:
```
INPUT:  {payload: {event_type: "PaymentCompleted", payload: "{...}"}}
OUTPUT: {payment_id: "...", amount: 100000}
```

### CDC Guarantees

- At-least-once delivery (replays unacknowledged LSN on restart)
- Ordered by insertion order (within partition/transaction)
- No dual-write problem (outbox INSERT in same DB transaction as business data)

## 2.4 Kafka Streams

Java library for stream processing. Runs in your application — no separate cluster.

| Use Case | Consumer API | Kafka Streams |
|----------|:-----------:|:------------:|
| Simple consume→process→produce | ✓ | Overkill |
| Join two topics | Manual state | ✓ KStream-KTable join |
| Windowed aggregation | Complex custom | ✓ Built-in |
| Exactly-once | Manual | ✓ `processing.guarantee=exactly_once_v2` |

### Example: Fraud Velocity Counter

```java
KStream<String, Payment> payments = builder.stream("payments.payment.created");
KStream<String, Long> velocity = payments
    .groupByKey()
    .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)))
    .count()
    .toStream()
    .filter((userId, count) -> count > 10);
velocity.to("fraud.velocity.alerts");
```

**RocksDB state stores**: Local embedded DB for state. Backed up to Kafka changelog topic. On instance failure, replay changelog to rebuild.

## 2.5 Exercises

### Ex 2.1 — Schema Evolution
Register v1 Avro schema. Produce 100 records. Register v2 (add optional field). Verify v1 consumers read v2 records. Try to delete a required field → Schema Registry rejects (BACKWARD violation).

### Ex 2.2 — Debezium CDC
Set up Debezium PostgreSQL connector. Insert into outbox_events table. Observe CDC publishes clean events to Kafka. Kill connector. Restart. Verify no duplicate events (idempotent consumer needed).

### Ex 2.3 — Kafka Streams
Build a 5-minute sliding window velocity counter using Kafka Streams. Test with time-ordered and out-of-order events.

## 2.6 Self-Assessment

- [ ] Understand at-least-once vs exactly-once trade-offs and when each is appropriate
- [ ] Can register and evolve Avro schemas with BACKWARD compatibility
- [ ] Can explain the Debezium CDC pipeline: pgoutput → Kafka Connect → EventRouter SMT → Topic
- [ ] Know when to use Kafka Streams vs plain Consumer API
