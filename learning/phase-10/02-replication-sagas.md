# Module 02 — Replication, Sharding, Distributed Transactions & Sagas

## 2.1 Replication Strategies

| Strategy | How | Consistency | Use Case |
|----------|-----|:----------:|----------|
| **Single-leader** | All writes to leader, reads from replicas | Strong (read leader), Eventual (read replica) | PostgreSQL streaming replication |
| **Multi-leader** | Multiple leaders accept writes, conflict resolution | Eventual (conflicts resolved) | CouchDB, multi-region apps |
| **Leaderless** | Quorum writes/reads (Dynamo-style) | Tunable (R+W > N = strong) | Cassandra, DynamoDB |

**Payment platform**: Single-leader. PostgreSQL primary handles all writes. Read replicas for reporting and transaction history. Replication lag acceptable for eventually-consistent reads.

## 2.2 Partitioning & Sharding

### Key-Range Sharding

Shard by account_id ranges: `A-M → Shard1, N-Z → Shard2`. Simple, range queries work. BUT: hot spots (all popular accounts in same shard).

### Hash Sharding

`hash(key) % num_shards`. Uniform distribution, no hot spots. BUT: loses range queries. Adding shards requires rebalancing (hash changes).

### Consistent Hashing

Virtual nodes on a ring. Each physical node gets multiple virtual nodes. Adding/removing nodes affects only neighboring virtual nodes. Used by: Redis Cluster, Cassandra, DynamoDB.

### Payment Sharding Strategy

Shard `journal_entries` by `account_id` hash. All entries for the same account go to the same shard → balance queries are single-shard. Cross-account transfers require distributed transactions (sagas).

## 2.3 Distributed Transactions: 2PC and Why It Fails

### Two-Phase Commit (2PC)

```
Coordinator                    Participants (Payment, Ledger)
    │                                │
    │──── PREPARE ──────────────────▶│  Lock resources, write to undo/redo log
    │◀─── VOTE (YES/NO) ─────────────│
    │                                │
    │──── COMMIT ───────────────────▶│  Release locks, make changes visible
    │◀─── ACK ───────────────────────│
```

**Why 2PC fails at scale**:
- **Blocking**: If coordinator crashes after sending PREPARE, participants are BLOCKED (locks held, can't decide). 3PC adds a timeout but doesn't fully solve it.
- **Latency**: Two round trips + disk flushes per participant. Payment can't wait 50ms for ledger + fraud + fee engine in sequence.
- **Lock contention**: All participants hold locks for the full duration. Wallet balance locked for 50ms → throughput drops from 10,000/s to 20/s.

## 2.4 Sagas — The Payment Pattern

A saga breaks a distributed transaction into a sequence of LOCAL transactions. Each step has a **compensating transaction** (semantic undo).

### Payment Saga Example

```
Step 1 [Retryable]:   FraudCheck(payment)  → {score, decision}
Step 2 [Retryable]:   FeeCalculation(payment) → {fee_amount}
Step 3 [Pivot]:       CreateJournalEntry(payment) → {entry_id}
         Compensation: CreateReversalEntry(entry_id)
Step 4 [Retryable]:   SendNotification(payment)
```

If Step 3 fails: saga executes compensation for Steps 2 and 1 (if needed, but they're retryable/idempotent — just skip). Payment state → FAILED.

### Step Classification

| Type | Retry? | Has Compensation? | Example |
|------|:------:|:-----------------:|---------|
| **Retryable** | Yes | No (idempotent) | Fraud check, fee calculation |
| **Pivot** | No | Yes | Ledger write (compensate with reversal) |
| **Irrevocable** | No | No (cannot undo) | SWIFT transfer sent |

### Orchestration vs Choreography

- **Orchestration**: Central coordinator (Payment Saga Orchestrator) calls each step. Centralized state, easier to understand. Used by our platform.
- **Choreography**: Services react to events. No central coordinator, more loosely coupled. Harder to debug.

## 2.5 Exercises

### Ex 2.1 — Payment Saga Implementation
Implement the 4-step payment saga. Each step can succeed or fail with configurable probability. Implement compensating transactions. Test failure at each step. Verify consistency.

### Ex 2.2 — What If Compensation Fails?
Step 3 (ledger write) succeeds. Step 4 fails. Compensation for Step 3 (reversal) ALSO fails. What do you do? Design the alert + manual reconciliation process. Write a reconciliation query that detects these orphaned states.

### Ex 2.3 — Consistent Hashing Simulation
Implement a consistent hashing ring with virtual nodes. Add/remove nodes. Measure: percentage of keys that need to move. Compare with hash(key) % num_shards.

## 2.6 Self-Assessment

- [ ] Can explain why 2PC is unacceptable for payment systems at scale
- [ ] Can design a saga for any multi-step business process
- [ ] Understand the difference between retryable, pivot, and irrevocable steps
- [ ] Know what to do when a saga compensation fails
- [ ] Can implement consistent hashing and explain why it minimizes data movement
