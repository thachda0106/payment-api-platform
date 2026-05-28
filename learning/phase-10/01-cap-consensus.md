# Module 01 — CAP, Consistency & Consensus

## 1.1 CAP Theorem in Payment Systems

CAP: Consistency, Availability, Partition tolerance. During a network partition, you choose 2. The "P" is non-negotiable (partitions happen). So you choose between CP and AP.

### Payment Platform Partition Decisions

| Call Site | Choice | During Partition | Rationale |
|-----------|:------:|-----------------|-----------|
| Payment → Financial Core (ledger write) | **CP** | BLOCK the payment | Cannot create money without ledger. No fallback. |
| Payment → Fraud Service | **AP** | USE CACHED SCORE (stale, 5 min old) | 99.9% of payments legitimate. Acceptable risk. |
| Payment → Fee Engine | **AP** | USE DEFAULT FEE TABLE | Overcharge slightly vs block ALL payments. Business decision. |
| Payment → Notification | **AP** | QUEUE + RETRY | Notification is eventually consistent. |
| Transaction History (read) | **AP** | SERVE STALE DATA | Better stale than unavailable. |

**The real question isn't "CP or AP?" It's "What happens at THIS specific call site during a partition?"** Different call sites have different answers.

## 1.2 Consistency Models

| Model | Guarantee | Payment Example |
|-------|-----------|----------------|
| **Linearizability** | All ops appear atomic at a single point in time | Ledger: after debit commits, ALL subsequent reads MUST see it |
| **Sequential** | Ops appear in some total order per-process | Order of events from different wallets may differ |
| **Causal** | Causally related writes seen in order | Payment succeeded → notification MUST see payment |
| **Eventual** | If no new writes, eventually all replicas converge | Transaction history: may lag 2-3 seconds |
| **Read-Your-Writes** | After you write, you read your own write | After top-up, immediate balance check MUST show new balance |

**Payment strategy**: Ledger = Linearizable (SERIALIZABLE). Balance after own write = Read-Your-Writes (query primary). Transaction history = Eventual (CQRS read model).

## 1.3 Consensus — Raft

Raft decomposes consensus into: Leader Election → Log Replication → Safety.

### Leader Election

Time divided into **terms** (monotonically increasing). Each term has at most one leader. Nodes start as followers. If no heartbeat within `election timeout` (150-300ms random), become candidate, increment term, vote for self, send `RequestVote` to all nodes. Candidate wins with majority votes.

**Safety constraint**: Candidate's log must be at least as up-to-date as voter's log (higher term OR same term + longer log). This prevents a node with missing entries from becoming leader.

### Log Replication

1. Client sends command to leader. Leader appends to log (uncommitted).
2. Leader sends `AppendEntries` RPC to followers (with new entries + `prevLogIndex`, `prevLogTerm`).
3. Follower checks: does its log match at `prevLogIndex`? If yes, appends. If no, rejects.
4. Leader retries with earlier `prevLogIndex` until match found.
5. When entry replicated to majority → COMMITTED → applied to state machine → returns to client.
6. Leader includes `leaderCommit` index in next AppendEntries → followers commit.

### Multi-Paxos vs Raft

Raft IS Multi-Paxos simplified. Skip Phase 1 (Prepare) once leader stable — just Accept directly (1 RTT instead of 2). Raft adds: constrained leader election, forced log consistency, joint consensus for membership changes.

### Payment Platform Consensus Use

- **Kafka**: KRaft (Raft variant) for metadata consensus. Controller election IS leader election.
- **PostgreSQL**: Synchronous replication uses quorum-like semantics. `synchronous_commit = remote_apply` waits for standby acknowledgment.
- **etcd**: Kubernetes control plane store. Uses Raft. If etcd is partitioned, no new pods can be scheduled.

## 1.4 Leader Election Patterns

### ZooKeeper Ephemeral Sequential Znodes

1. Each candidate creates `/election/candidate-` with EPHEMERAL + SEQUENTIAL flags → gets `/election/candidate-0000000003`
2. Candidate with LOWEST sequence number is the leader
3. Others watch the znode just before theirs in sequence
4. Leader znode deleted (session timeout or explicit) → next candidate becomes leader

### Split-Brain Prevention

**Fencing Token**: Monotonically increasing token issued by the coordination service. Every write to the resource includes the fencing token. If a stale leader (one that was partitioned) tries to write with an old token, the resource REJECTS it.

```
Leader A (term 5, token 5) writes to DB: "SET balance=100 WHERE token=5" → Accepted
Leader A partitioned. Leader B elected (term 6, token 6).
Leader A (still thinks it's leader, token 5) writes: "SET balance=200 WHERE token=5" → REJECTED!
```

## 1.5 Exercises

### Ex 1.1 — Raft Simulation
Implement simplified Raft in any language: 3 nodes, leader election, log replication. Test: leader crashes mid-replication, network partition isolates one node, simultaneous election.

### Ex 1.2 — CAP Decision Matrix
For each of your payment platform's 10 cross-service calls, document: (a) CP or AP choice, (b) rationale, (c) fallback behavior during partition.

### Ex 1.3 — Consistency Demonstration
Set up a PostgreSQL primary + read replica. Write to primary. Immediately read from replica. Observe: replication lag. Demonstrate why Read-Your-Writes requires reading from primary after a write.

## 1.6 Self-Assessment

- [ ] Can explain CAP and decide CP vs AP for any given call site
- [ ] Can walk through Raft leader election and log replication from memory
- [ ] Understand why fencing tokens prevent split-brain
- [ ] Know the difference between Linearizability and Serializability
- [ ] Can choose the right consistency model for each payment operation
