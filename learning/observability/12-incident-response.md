# Phase 12 — Incident Response

> **Duration**: 3-4 days | **Prerequisites**: Phases 1-8 (all tools understanding)
>
> **Goal**: Apply the full observability stack to real-world troubleshooting scenarios, following the Alert → Metric → Trace → Log path to root cause.

---

## 12.1 The Incident Response Workflow

### 12.1.1 The Golden Path

```
Alert Fires (PagerDuty/Slack)
    ↓
Triage: What's the blast radius?
    ↓
Dashboard: Which service? Which metric?
    ↓
Metric Deep Dive: Time correlation with deploys/config changes
    ↓
Exemplar Trace: Find a representative failing request
    ↓
Trace Waterfall: Which span is slow/failing?
    ↓
Related Logs: What's the error message?
    ↓
Root Cause Identified
    ↓
Mitigate (rollback, scale, circuit-break, restart)
    ↓
Resolve + Postmortem
```

### 12.1.2 Triage Questions (First 60 Seconds)

1. **WHAT**: What alert fired? What's the symptom?
2. **WHERE**: Which service? Which endpoint? Which region/AZ?
3. **WHEN**: Exact timestamp. Correlate with deploys, config changes, traffic spikes.
4. **HOW MUCH**: Is this impacting all users or a subset? What's the error rate?
5. **IS IT GETTING WORSE**: Trending up, stable, or self-resolving?

If answers 1-3 are unknown, the observability stack is insufficient. If answers 4-5 are unknown within 2 minutes, dashboards are insufficient.

---

## 12.2 Scenario 1: API Latency Spike

### 12.2.1 Alert

```
Alert: HighP99Latency
Service: payment-service
Endpoint: POST /payments
Value: p99 = 2350ms (SLO = 500ms)
Duration: 5 minutes
Started: 14:23:00 UTC
```

### 12.2.2 Triage

**Grafana → Payment Service RED Dashboard:**

```
Duration Panel (last 30 min):
  ═══ p99 latency
  ··· p50 latency

  14:00 ▁▁▁▁▁▁▁▁▁ 50ms
  14:05 ▁▁▁▁▁▁▁▁▁ 48ms
  14:10 ▁▁▁▁▁▁▁▁▁ 52ms
  14:15 ▁▁▁▁▁▁▁▁▁ 55ms
  14:20 ▁▂▃▅▇███ 200ms → 2350ms  ← SPIKE
  14:25 ████████ 2100ms

Error Rate Panel:
  14:00 0.01%
  14:20 0.02%  (errors are NOT spiking — it's slow, not failing)
  14:25 0.01%

Request Rate Panel:
  14:00 800/s
  14:20 850/s  (no traffic spike — it's not load-related)
  14:25 840/s
```

**Initial assessment**: Latency spiked, but error rate didn't. Traffic is stable. This is a performance degradation, not a failure.

### 12.2.3 Metric Correlation

**What changed at 14:20?**

```
Deployment Dashboard:
  14:18  payment-service v2.4.1 deployed  ← CORRELATES
  14:19  payment-service v2.4.1 rollback  ← Someone tried to fix

Database Dashboard:
  PostgreSQL p99 query latency spiked at 14:20
  Connection pool utilization: 18/20 (90%) at 14:23 (but was 5/20 before)
```

**Hypothesis**: Deployment v2.4.1 changed database query patterns, causing slow queries that hold connections longer, causing connection pool near-exhaustion. Latency spikes because threads wait for connections.

### 12.2.4 Trace Investigation

**Grafana → Click latency spike at 14:23 → Exemplar trace:**

```
Trace: a1b2c3d4e5f6a1b2  (duration: 2450ms)

POST /payments  [2450ms]                       ← server entry
├── Validate Payment  [5ms]
├── Auth (gRPC)  [15ms]                         ← external call, fast
├── Fraud Check [420ms]                         ← external call, normal
└── Ledger Service [1980ms]  ← BOTTLENECK     ← external call, SLOW
    └── PostgreSQL INSERT [1975ms]  ← THE PROBLEM
        └── Query: INSERT INTO journal_entries (...)
                VALUES (...) RETURNING *
```

**The trace tells the story**: LedgerService → PostgreSQL INSERT takes 1975ms. The database is the bottleneck.

### 12.2.5 Log Investigation

**Grafana → Click the slow span → "View Related Logs" (filtered by trace_id):**

```
OpenSearch Logs for trace=a1b2c3d4e5f6a1b2:

14:23:45.123 [ledger-service] INFO  Processing ledger entry for payment pay_123
14:23:45.150 [ledger-service] DEBUG Executing query on payments-db
14:23:47.127 [ledger-service] WARN  Slow query detected: 1975ms
  query="INSERT INTO journal_entries (id, amount, ...) VALUES ($1, $2, ...)"
  plan="Seq Scan on journal_entries"

-- That's weird. INSERT with Seq Scan? Let's look at the schema.

14:23:10 [DBA] INFO  Index maintenance completed on journal_entries
```

**The seq scan on INSERT is suspicious.** INSERTs shouldn't cause sequential scans — unless there's a trigger or a constraint check that does one. But the DBA ran index maintenance at 14:23...

**Wait — check the EXPLAIN:**

```sql
EXPLAIN ANALYZE
INSERT INTO journal_entries (id, account_id, debit, credit)
VALUES ('pay_123', 'acc_456', 100, 0);

-- Plan:
-- Insert on journal_entries
--   -> Result
-- Trigger: check_account_balance:
--   -> Seq Scan on journal_entries  ← This is the problem!
--   Filter: account_id = 'acc_456'
```

**Root cause**: A trigger `check_account_balance` runs `SELECT SUM(debit - credit) FROM journal_entries WHERE account_id = $1` on every INSERT. The `account_id` index was dropped during maintenance at 14:18. Without the index, every INSERT causes a full table scan of `journal_entries` (millions of rows → 2 seconds each).

### 12.2.6 Mitigation

1. **Immediate**: Re-create the index on `journal_entries(account_id)`
2. **Verify**: p99 latency returns to 50ms within 60 seconds
3. **Reflect**: Why was the index dropped? Maintenance script had a bug that dropped non-unused indexes.

### 12.2.7 Timeline

```
14:18  v2.4.1 deployed (unrelated — just coincidence)
14:18  DBA runs index maintenance script (bug: drops account_id index on journal_entries)
14:20  Latency starts rising (INSERTs now full-table-scan 10M rows)
14:23  P99 exceeds 500ms SLO → Alert fires
14:24  Engineer responds, opens dashboard
14:25  Identifies PostgreSQL is the bottleneck via trace
14:26  Finds the missing index via EXPLAIN ANALYZE
14:27  Re-creates index
14:28  P99 returns to normal
14:29  Incident resolved

Time to detect:  3 minutes (alert)  ✓ Good
Time to triage:  2 minutes          ✓ Good
Time to root cause: 2 minutes       ✓ Good
Time to mitigate: 1 minute          ✓ Good
Total MTTR: 8 minutes
```

---

## 12.3 Scenario 2: Database Connection Pool Exhaustion

### 12.3.1 Alert

```
Alert: ConnectionPoolNearlyExhausted
Service: ledger-service
Value: 18/20 connections active (90%)
Duration: 10 minutes
```

### 12.3.2 Triage

```
Grafana → Ledger Service Resource Dashboard:

DB Connection Pool Panel (last 30 min):
  (Active / Max)
  14:00 ▁▁▁▁ 4/20  (20%)
  14:10 ▁▁▁▁ 4/20  (20%)
  14:15 ▃▅▇█ 12/20 (60%)  ← starts climbing
  14:20 ████ 18/20 (90%)  ← near exhaustion
  14:25 ████ 19/20 (95%)

DB Query Duration Panel:
  p50: 45ms → 45ms (stable)
  p95: 200ms → 890ms (rising)
  p99: 500ms → 3200ms (critical)

Idle-in-Transaction Count:
  14:00 0
  14:15 2
  14:20 7  ← Connections held open in idle transactions
  14:25 12
```

**Key finding**: Idle-in-transaction count is rising. Connections are being acquired but not released. This points to a code bug: someone is opening a transaction but not closing it.

### 12.3.3 Trace Investigation

**Filter traces by `ledger-service` + `duration > 1000ms`:**

```
Slow trace: POST /refund  [12000ms]

Span: POST /refund  [0ms - 12000ms]
├── Span: Acquire Connection [5ms - 1800ms]  ← WAITING for 1795ms
├── Span: BEGIN TRANSACTION [1800ms - 1802ms]
├── Span: Query Wallet [1803ms - 1845ms]
├── (no COMMIT span!)  ← TRANSACTION NOT CLOSED
└── Span: POST /notify  [2000ms - 11800ms]  ← HTTP call to slow notification service
    └── (notification service takes 9800ms, then times out)

--- After HTTP timeout exception ---
    Span catches exception → returns error → BUT TRANSACTION NOT ROLLED BACK!
    Connection returns to pool with open transaction
    Next user gets connection → BEGIN creates nested transaction → held forever
```

**Root cause**: The refund endpoint opens a transaction, makes a slow HTTP call to the notification service (inside the transaction!), and if the HTTP call times out, the exception handler doesn't roll back the transaction. The connection returns to the pool with an open transaction.

### 12.3.4 Code Analysis

```java
// BUG: Transaction not closed on exception
@PostMapping("/refunds")
public RefundResponse createRefund(@RequestBody RefundRequest req) {
    Connection conn = dataSource.getConnection();
    conn.setAutoCommit(false);  // Begin transaction
    try {
        Wallet wallet = walletRepo.findByUserId(conn, req.getUserId());
        wallet.credit(req.getAmount());
        walletRepo.save(conn, wallet);

        // BUG: HTTP call inside transaction
        notificationService.notify(req);  // This can take 10s+!

        conn.commit();
        return new RefundResponse("ok");
    } catch (Exception e) {
        // BUG: No rollback!
        conn.close();  // Returns connection to pool WITHOUT rollback
        throw e;
    }
}
```

**Fix:**

```java
@PostMapping("/refunds")
public RefundResponse createRefund(@RequestBody RefundRequest req) {
    // Move external call OUTSIDE the transaction
    // Do DB work first, THEN notify

    Connection conn = dataSource.getConnection();
    conn.setAutoCommit(false);
    try {
        Wallet wallet = walletRepo.findByUserId(conn, req.getUserId());
        wallet.credit(req.getAmount());
        walletRepo.save(conn, wallet);
        conn.commit();
    } catch (Exception e) {
        conn.rollback();  // ALWAYS rollback
        throw e;
    } finally {
        conn.setAutoCommit(true);
        conn.close();
    }

    // External call AFTER transaction is closed
    notificationService.notify(req);  // Can fail without affecting DB state

    return new RefundResponse("ok");
}
```

### 12.3.5 The Three Rules for Transactions

1. **Transactions should be as SHORT as possible.** No network calls inside transactions. Ever.
2. **Every transaction must be committed or rolled back.** Use try-catch-finally or try-with-resources.
3. **External service calls come AFTER the transaction closes.** If the external call fails after commit, use sagas/compensation for consistency.

---

## 12.4 Scenario 3: Kafka Consumer Lag

### 12.4.1 Alert

```
Alert: KafkaConsumerLag
Service: settlement-processor
Group: settlement-group
Topic: payments-completed
Lag: 120,000 messages (threshold: 10,000)
Duration: 15 minutes
```

### 12.4.2 Triage

```
Grafana → Kafka Dashboard:

Consumer Lag per Partition:
  Partition 0:  45,000 messages  (growing)
  Partition 1:  40,000 messages  (growing)
  Partition 2:  35,000 messages  (growing)
  All partitions lagging equally ← NOT a partition-specific issue

Consumer Throughput:
  14:00  800 msg/s
  14:10  800 msg/s
  14:15  300 msg/s  ← DROPPED
  14:20  100 msg/s  ← CRASHING

Producer Throughput:
  14:00  800 msg/s  (stable throughout)
  14:15  820 msg/s
  14:20  810 msg/s

  Producer rate > Consumer rate → Lag grows
```

Lag growing because consumer throughput DROPPED, not because producer rate increased.

### 12.4.3 Trace Investigation

**Jaeger → Filter by `settlement-processor` + time range:**

```
Consumer traces are stuttering — some complete in 50ms, some take 30,000ms.

Slow trace: consume-settlement  [32000ms]
├── Kafka Poll  [2ms]
├── Deserialize  [1ms]
├── Process Settlement  [31995ms]
│   └── PostgreSQL SELECT FOR UPDATE  [31980ms]  ← THE PROBLEM
│       Lock wait: waiting for transaction on wallets table
│
└── Commit Offset  [2ms]
```

A `SELECT FOR UPDATE` on the wallets table is waiting for a lock. Another transaction is holding that lock.

### 12.4.4 Log Investigation

**OpenSearch → `settlement-processor` logs at 14:15:**

```
14:15:10  INFO  Processing settlement for payment pay_456
14:15:11  WARN  Lock wait timeout for wallet WALLET-789
14:15:12  ERROR Failed to acquire lock on wallet WALLET-789: 
               Lock held by process: manual-adjustment-job (PID: 12934)
14:15:13  WARN  Retry 2/5 for settlement pay_456
14:15:14  WARN  Lock wait timeout for wallet WALLET-789
...
14:20:00  (consumer keeps retrying, never commits offsets)
```

**Root cause**: A manual-adjustment DBA job at 14:10 started a long-running transaction (`UPDATE wallets SET balance = ... WHERE id = WALLET-789`) that's holding a row lock. Every settlement that touches WALLET-789 waits for this lock. The lock cascade blocks the entire settlement consumer, which can't commit offsets (because it's waiting), causing lag.

### 12.4.5 Mitigation

1. **Immediate**: Kill the manual-adjustment job (rollback, release lock)
2. **Verify**: Consumer lag starts decreasing immediately
3. **Long-term**: Manual adjustments should run during maintenance windows. Add lock timeout to settlement queries (`SET lock_timeout = '5s'`).

---

## 12.5 Scenario 4: Retry Storm / Cascading Failure

### 12.5.1 Alert

```
Multiple simultaneous alerts:
  - HighErrorRate: payment-service (12%)
  - HighErrorRate: auth-service (8%)
  - HighP99Latency: notification-service (15s)
  - KafkaConsumerLag: all groups (growing)
  - CPUSaturation: 15/20 nodes > 85%
```

**This is a cascading failure pattern.** Multiple alerts across services. CPU saturated cluster-wide. All consumers lagging. Something broke broadly.

### 12.5.2 Triage — Find the Origin

```
Timeline across all service RED dashboards:

14:00:00  notification-service p99 = 100ms  (normal)
14:00:05  notification-service p99 = 500ms  (slowing)
14:00:10  notification-service p99 = 5000ms (slow)

14:00:12  payment-service error rate = 0.1% → 5%
           Error: "notification service timeout" (5s timeout, notification taking 5s+)

14:00:15  payment-service increases retries (3 retries × 500 req/s = 1500 req/s to notification)
14:00:18  notification-service overloaded (1500 req/s vs capacity 1000 req/s)
14:00:20  notification-service p99 = 15000ms (15 seconds)

14:00:22  auth-service calls payment-service, payment slow → auth clients timeout
14:00:25  auth-service retries payment requests → more load on payment
14:00:30  payment-service CPU 95% (retries + original traffic)
14:00:35  ALL services slow. ALL consumers lagging. Cluster saturated.

Root cause: notification-service degraded → payment retried → overload cascade
```

### 12.5.3 The Retry Storm Mechanism

```
User Request
    ↓
Payment Service (calls Notification)
    ↓ Timeout after 5s (notification is slow)
Payment Service retries
    ↓ (Retry 2) Timeout
Payment Service retries again
    ↓ (Retry 3) Timeout
Payment Service returns ERROR to Auth Service
    ↓
Auth Service retries payment call
    ↓
Each auth retry = new payment call = new notification call
    ↓
NotificationService: 1 initial request → 9 retry requests (3 per service × 3 services)
    ↓
NotificationService crashes completely
    ↓
All services timing out, retrying, saturating resources
    ↓
Entire cluster degraded
```

### 12.5.4 Mitigation Patterns

**1. Circuit Breaker** — Stop calling failing dependencies:

```java
@CircuitBreaker(
    name = "notification-service",
    failureRateThreshold = 50,      // 50% failures
    waitDurationInOpenState = 30000 // Open for 30 seconds
)
public void notify(Notification n) {
    notificationClient.send(n);
}
```

When the notification service fails 50% of calls, the circuit breaker OPENS. For 30 seconds, no calls are made (they fail fast with `CircuitBreakerOpenException`). After 30 seconds, HALF-OPEN (try a few requests). If they succeed, CLOSE (resume normal). If they fail, OPEN again.

**2. Retry with Exponential Backoff + Jitter:**

```java
@Retry(
    maxAttempts = 3,
    backoff = @Backoff(delay = 100, multiplier = 2),  // 100ms, 200ms, 400ms
    jitter = @Backoff.Random(min = 0, max = 100)       // Add randomness
)
public void notify(Notification n) { ... }
```

Without jitter, all retries from all instances fire at exactly the same time (100ms, 200ms, 400ms), creating synchronized load spikes (the "thundering herd" problem). Jitter spreads them randomly.

**3. Bulkhead** — Isolate thread pools per dependency:

```yaml
# Each downstream dependency gets its own bounded thread pool
payment-service:
  notification-thread-pool:
    core: 10
    max: 10
    queue: 50
  fraud-thread-pool:
    core: 10
    max: 20

# If notification fails and blocks all 10 threads, fraud still works
```

---

## 12.6 Incident Response Principles

### 12.6.1 Prioritize Detection and Mitigation, NOT Root Cause

During an active incident:

1. **Detect** (should be automatic via alerts)
2. **Mitigate** (restore service — rollback, scale, circuit-break)
3. **Root Cause** (find WHY it happened — can wait until after mitigation)

Don't debug during the outage. Mitigate first, investigate later.

### 12.6.2 The Five-Minute Rule

If you haven't identified the problem in 5 minutes:
- **Escalate** (bring in more people)
- **Mitigate speculatively** (rollback the last deploy, even if unsure)
- **Communicate** (update stakeholders — don't wait for answers)

### 12.6.3 Always Check Deployments First

90% of production incidents are caused by recent changes. Before deep-diving into metrics:
1. Check deployment history (last 30 minutes)
2. Check configuration changes (feature flags, env vars, secrets rotation)
3. Check infrastructure changes (scaling events, node replacements)

---

## 12.7 Common Misconceptions

### "I can debug everything from the alert alone"

An alert tells you SOMETHING is wrong, not WHAT. The alert is the doorbell. You still need to open the door (dashboards), walk through the house (traces), and check the room (logs).

### "More retries = more reliable"

Retries AMPLIFY load during degradation. A service degradation at 2x normal latency causes 3x retry load, which causes 4x degradation. Retries without circuit breakers and exponential backoff cause cascading failures.

### "Root cause analysis must happen during the incident"

Root cause analysis happens in the POSTMORTEM. During the incident, you mitigate. The postmortem is where you answer "why did this happen and how do we prevent it?" Blameless postmortems improve systems; root-causing during incidents delays recovery.

---

## Interview Questions — Phase 12

1. **Walk through your incident response process for an unknown alert at 3 AM.**

   *Answer core points*: (1) Acknowledge alert, check severity. (2) Open service RED dashboard — identify which endpoint is affected. (3) Check deployment timeline for recent changes. (4) Find exemplar trace for the latency/error spike. (5) Identify bottleneck span in trace waterfall. (6) Check related logs for error messages. (7) If root cause found → mitigate. If not within 5 min → escalate. (8) Communicate status. (9) Postmortem.

2. **A payment service shows high latency but NO errors. A downstream notification service shows high errors. What's happening?**

   *Answer core points*: This is a cascading failure. Payment calls notification. Notification is slow (not failing — still responding). Payment's HTTP client times out after 5 seconds, which registers as a latency spike in payment (successful responses at 5s) but errors in notification (client disconnects mid-response). If payment retries, the load amplifies. The fix is NOT increasing the timeout — it's adding a circuit breaker on the notification call.

3. **Explain the retry storm problem. How do you prevent it?**

   *Answer core points*: Retries multiply traffic during degradation. Service A calls B. B slows down. A times out, retries 3×. Now B receives 4× traffic while already degraded. This cascades to A's callers who also retry. Prevention: (1) Circuit breaker (stop calling after threshold failures), (2) Exponential backoff + jitter (spread retry timing), (3) Bulkhead (isolate thread pools so one degraded downstream doesn't consume all threads), (4) Timeout should be LESS than max retry budget.

4. **What's the first thing you check when any alert fires?**

   *Answer core points*: "What changed recently?" Check deployment history, config changes, infrastructure changes in the last 30 minutes. 90% of incidents are caused by changes. Finding the change often identifies both the root cause AND the mitigation (rollback) immediately, without needing deep metric/trace analysis.

---

**Next: Phase 13 — Observability for Senior Backend Engineers**
