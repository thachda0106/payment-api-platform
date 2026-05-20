# Module 04 — Idempotency & Distributed Consistency

## Duration: 3–4 hours | Critical: Yes

---

## Learning Objectives

By the end of this module, you will understand:
- What idempotency means and why it's non-negotiable in payments
- How to design idempotency keys and enforce them at the API gateway
- The Outbox pattern: why dual-write is dangerous and how CDC fixes it
- Saga patterns for distributed transactions across services
- How `SELECT FOR UPDATE` prevents race conditions inledger
- The difference between exactly-once, at-least-once, and at-most-once

---

## 1. What Is Idempotency?

**Definition**: An operation is idempotent if performing it multiple times has the same effect as performing it once.

### In Payments:

```
POST /api/v1/transactions  {  "amount": 50000, "sender": "A", "receiver": "B" }
```

If the client sends this request **twice** (e.g., network timeout, user double-click):
- **Without idempotency**: User A is charged 100,000 VND instead of 50,000 VND
- **With idempotency**: Second request returns the same result as the first. User A is charged exactly 50,000 VND.

### NOT All HTTP Methods Are Born Equal

| Method | Idempotent? | Safe? |
|--------|:---:|:---:|
| GET | Yes | Yes |
| PUT | Yes | No |
| DELETE | Yes (generally) | No |
| POST | **No** | No |
| PATCH | No (unless designed) | No |

Payment APIs are mostly POST. That means you MUST implement idempotency explicitly.

---

## 2. Idempotency Key Pattern

### How It Works

```http
POST /api/v1/transactions HTTP/1.1
Host: api.payment-platform.com
Content-Type: application/json
X-Idempotency-Key: 7b1a9e3f-d8c2-4a56-b789-0123456789ab

{
  "sender_id": "user-A",
  "receiver_id": "user-B",
  "amount": 50000,
  "currency": "VND"
}
```

1. Client generates a UUID for each unique operation.
2. Server checks: has this key been seen before?
   - **No**: Execute the operation. Store `{key → response}` in Redis with TTL 24h.
   - **Yes**: Return the stored response (idempotent replay). Do NOT re-execute.
3. If the first request succeeded (200 OK) but the client didn't receive the response (network failure), the retry with the same key returns the same 200 OK.

### Implementation

```go
type IdempotencyMiddleware struct {
    cache redis.Client
}

func (m *IdempotencyMiddleware) Wrap(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        if r.Method != "POST" && r.Method != "PATCH" {
            next.ServeHTTP(w, r)
            return
        }

        key := r.Header.Get("X-Idempotency-Key")
        if key == "" {
            http.Error(w, "Missing X-Idempotency-Key", http.StatusBadRequest)
            return
        }

        // Check for existing result
        cached, err := m.cache.Get(r.Context(), "idempotent:"+key).Result()
        if err == redis.Nil {
            // First time — proceed
            next.ServeHTTP(w, r)
            return
        }
        if err != nil {
            http.Error(w, "Internal error", http.StatusInternalServerError)
            return
        }

        // Returning cached response
        w.Header().Set("Idempotent-Replay", "true")
        w.Write([]byte(cached))
    })
}
```

### Storing Response After First Execution

```go
func (s *Service) ProcessPayment(ctx context.Context, req PaymentRequest) (*PaymentResponse, error) {
    idempKey := req.IDKey // from X-Idempotency-Key header

    // Check idempotency BEFORE any side effects
    cached, err := s.idempotencyCache.Get(ctx, idempKey)
    if cached != nil {
        return cached.Response, nil // Return stored response
    }

    // Transactional block: execute AND store result atomically
    resp, err := s.executePayment(ctx, req)
    if err != nil {
        return nil, err
    }

    // Store response BEFORE committing to ledger
    // (This is a simplified example — real implementation uses Outbox)
    s.idempotencyCache.Set(ctx, idempKey, resp, 24*time.Hour)

    return resp, nil
}
```

### Key Design Decisions

| Decision | Why |
|----------|-----|
| **TTL = 24h** | Covers network retry windows, allows debugging |
| **Hash key + JWT.sub** | Prevents user A from replaying user B's key |
| **Redis for storage** | Sub-ms latency, automatic TTL |
| **Response includes idempotency key** | Client can verify which key was used |

---

## 3. The Outbox Pattern

### The Problem: Dual-Write

```go
func ProcessPayment(ctx context.Context, req PaymentRequest) error {
    // Step 1: Deduct balance
    err := db.DeductBalance(ctx, req.SenderID, req.Amount)
    if err != nil {
        return err
    }

    // Step 2: Publish event → WHAT IF THIS FAILS?
    err = kafka.Publish(ctx, "payment.created", event)
    if err != nil {
        // Now: balance deducted, event lost → inconsistency!
        // Can't rollback DB (already committed)
    }
    return nil
}
```

**Dual-write consistency problem**: If the DB write succeeds but the Kafka publish fails, the system is inconsistent. The balance shows the payment happened, but downstream systems never get notified.

### The Solution: Outbox Pattern

Instead of writing to DB AND publishing to Kafka directly, write an "outbox" record in the **same database transaction**:

```sql
-- Transaction A:
BEGIN;

-- 1. Perform business logic
UPDATE wallets SET balance = balance - 50000 WHERE id = 'user-A';

-- 2. Write to outbox table (same transaction!)
INSERT INTO outbox (id, aggregate_type, event_type, payload, created_at)
VALUES (
    gen_random_uuid(),
    'wallet',
    'wallet.balance.deducted',
    '{"wallet_id": "user-A", "amount": 50000}',
    NOW()
);

COMMIT;  -- Both write or neither writes
```

Then a separate process (Debezium CDC connector) reads the outbox table and publishes to Kafka:

```
PostgreSQL outbox table ──▶ Debezium CDC ──▶ Apache Kafka
   (WAL-based)                  (pgoutput)
```

**Result**: Exactly-once delivery guarantee. The DB and Kafka are always consistent.

### Outbox Table Schema

```sql
CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(100) NOT NULL,    -- e.g., 'wallet', 'payment'
    aggregate_id    VARCHAR(100) NOT NULL,     -- e.g., wallet ID
    event_type      VARCHAR(100) NOT NULL,     -- e.g., 'wallet.balance.deducted'
    payload         JSONB NOT NULL,
    trace_context   JSONB,                     -- OpenTelemetry context for trace continuity
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ               -- Set by CDC connector
);

-- Index for Debezium
CREATE INDEX idx_outbox_published ON outbox (published_at)
WHERE published_at IS NULL;
```

---

## 4. Saga Pattern for Distributed Transactions

### The Problem: Multiple Services, One Operation

```
Payment flow requires:
1. Wallet Service: Deduct sender balance
2. Ledger Service: Create journal entry
3. Merchant Service: Update receivable
4. Notification Service: Push notification
```

If step 2 fails after step 1 succeeds, the sender's money is gone without a ledger record. That's **money creation**.

### Choreography vs. Orchestration Saga

**Choreography** (event-driven, no central coordinator):

```
Wallet Service:
  1. Deduct balance
  2. Emit: "payment.deducted"

Ledger Service (listens to "payment.deducted"):
  1. Create journal entry
  2. Emit: "payment.ledgered"

Merchant Service (listens to "payment.ledgered"):
  1. Update receivable
  2. Emit: "payment.completed"
```

**Orchestration** (central coordinator):

```
Payment Orchestrator:
  1. Send command: DeductBalance {sender, amount}
     → Wallet replies: BalanceDeducted / InsufficientFunds
  2. Send command: CreateJournalEntry {transaction}
     → Ledger replies: JournalCreated / Failure
  3. Send command: UpdateReceivable {merchant, amount}
     → Merchant replies: ReceivableUpdated / Failure
```

### Compensating Transactions

If a step fails, earlier steps must be **compensated** (rolled back via a new operation, not by deleting the old one):

| Step | Compensation |
|------|-------------|
| Deduct balance | Reverse: credit balance back |
| Create journal entry | Reversing entry (not DELETE) |
| Add receivable | Subtract: reduce receivable |

```go
// Orchestrator handles failure with compensation
func (s *PaymentOrchestrator) ExecutePaymentSaga(ctx context.Context, req PaymentRequest) error {
    // Step 1
    if err := s.wallet.Deduct(ctx, req.SenderID, req.Amount); err != nil {
        return err // No compensation needed — nothing happened
    }

    // Step 2
    if err := s.ledger.CreateEntry(ctx, req); err != nil {
        // Compensate step 1
        s.wallet.Credit(ctx, req.SenderID, req.Amount) // Reverse deduction
        return err
    }

    // Step 3
    if err := s.merchant.UpdateReceivable(ctx, req); err != nil {
        // Compensate step 2 + step 1
        s.ledger.CreateReversalEntry(ctx, req) // Reversing entry
        s.wallet.Credit(ctx, req.SenderID, req.Amount)
        return err
    }

    return nil
}
```

**Critical Rule**: Compensating transactions must themselves be idempotent and recorded in the ledger.

---

## 5. Pessimistic Locking with SELECT FOR UPDATE

### The Race Condition

```go
// TWO concurrent requests for the same wallet

// Request 1:
balance := SELECT balance FROM wallets WHERE id = 'user-A'  -- 100,000
if balance >= 50,000 {
    UPDATE wallets SET balance = 50,000 WHERE id = 'user-A'  -- CORRECT
}

// Request 2 (between SELECT and UPDATE of Request 1):
balance := SELECT balance FROM wallets WHERE id = 'user-A'  -- STILL 100,000!
if balance >= 70,000 {
    UPDATE wallets SET balance = 30,000 WHERE id = 'user-A'  -- Should be -20,000!
}
```

### The Fix: SELECT FOR UPDATE

```go
// In a transaction:
tx, _ := db.Begin(ctx)

// LOCK the row — Request 2 will WAIT here until Request 1 commits
row := tx.QueryRow(ctx, "SELECT balance FROM wallets WHERE id = $1 FOR UPDATE NOWAIT", walletID)
//                                                                            ^^^^^^^^
// NOWAIT = fail immediately if locked (instead of waiting)

// Now safe to read and update:
if balance >= amount {
    tx.Exec(ctx, "UPDATE wallets SET balance = balance - $1 WHERE id = $2", amount, walletID)
}

tx.Commit() // Lock released
```

### PostgreSQL Lock Modes for Payment Operations

| Lock Mode | SQL | Use Case |
|-----------|-----|----------|
| **Row-level exclusive** | `SELECT ... FOR UPDATE` | **Default for payment operations**. Blocks other writes. |
| **Row-level skip locked** | `SELECT ... FOR UPDATE SKIP LOCKED` | Queue processing: take next unprocessed row, skip locked ones. |
| **Row-level no wait** | `SELECT ... FOR UPDATE NOWAIT` | Immediate fail instead of waiting. Used when you cannot block. |
| **Advisory lock** | `pg_advisory_xact_lock(id)` | Application-level mutex when row-level locking isn't appropriate. |
| **Table-level** | `LOCK TABLE` | Rarely needed. Used for batch operations (e.g., EOD settlement). |

---

## 6. Delivery Guarantees

| Guarantee | Meaning | How to Achieve |
|-----------|---------|----------------|
| **At-most-once** | Message may be lost, never duplicated | Fire-and-forget, no retries |
| **At-least-once** | Message delivered, but may be duplicated | Retry on failure, idempotent consumer |
| **Exactly-once** | Message delivered once and only once | Idempotent producer + Outbox + dedup on consumer |

### In Payment Systems

| Component | Required Guarantee | Why |
|-----------|-------------------|-----|
| Balance deduction | Exactly-once | Can't charge twice or miss a charge |
| Event publishing | At-least-once + idempotent consumer | Outbox ensures at-least-once; consumer deduplicates |
| Notification | At-least-once | A duplicate push notification is annoying but not catastrophic |
| Logging | At-most-once | Don't block the payment for logs |

---

## 7. Practical: Idempotent Payment Handler in Go

```go
func (h *Handler) CreateTransaction(w http.ResponseWriter, r *http.Request) {
    ctx := r.Context()

    var req CreateTransactionRequest
    json.NewDecoder(r.Body).Decode(&req)

    // Idempotency check (from middleware)
    idempKey := GetIdempotencyKey(ctx)
    if h.idempCache.Exists(ctx, idempKey) {
        writeJSON(w, http.StatusOK, h.idempCache.Get(ctx, idempKey))
        return
    }

    // Transaction with idempotency + outbox
    err := h.db.WithTransaction(ctx, func(tx pgx.Tx) error {
        // 1. Pessimistic lock
        _, err := tx.Exec(ctx, "SELECT id FROM wallets WHERE id = $1 FOR UPDATE NOWAIT", req.SenderID)
        if err != nil {
            return ErrConcurrentOperation
        }

        // 2. Business logic
        err = tx.Exec(ctx, "UPDATE wallets SET balance = balance - $1 WHERE id = $2 AND balance >= $1",
            req.Amount, req.SenderID)
        if err != nil {
            return ErrInsufficientBalance
        }

        // 3. Write idempotency record (same transaction!)
        err = tx.Exec(ctx,
            `INSERT INTO idempotency_keys (idempotency_key, response_status, response_body, created_at)
             VALUES ($1, $2, $3, NOW())
             ON CONFLICT (idempotency_key) DO NOTHING`,
            idempKey, 200, `{"status": "completed", "txn_id": "`+txnID+`"}`,
        )

        // 4. Outbox entry (same transaction!)
        err = tx.Exec(ctx,
            `INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
             VALUES ('payment', $1, 'payment.created', $2)`,
            req.SenderID, `{"txn_id": "`+txnID+`", "amount": `+strconv.FormatInt(req.Amount, 10)+`}`,
        )

        return nil
    })
}
```

---

## Check Questions

1. Why is POST not idempotent? How do we make it idempotent?
2. What happens if a client retries with the same X-Idempotency-Key after the first request failed with 500?
3. What's the dual-write problem? How does the Outbox pattern solve it?
4. What's the difference between choreography and orchestration sagas?
5. When would you use `SELECT FOR UPDATE NOWAIT` vs `SELECT FOR UPDATE SKIP LOCKED`?
6. If a saga step fails, why can't you just DELETE the previous side effect?
7. Can you give an example of a compensating transaction?
8. What delivery guarantee do balance deductions require? Why?

---

## Next Module

[Module 05 — Fraud Detection Fundamentals](05-fraud-detection-basics.md)

> Idempotency prevents bugs. Fraud prevention prevents thieves. Both must be designed before code is written.
