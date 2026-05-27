# Module 07 — Go Language & Ecosystem for Payment Systems

## Duration: 4–5 hours | Critical: Yes

---

## Learning Objectives

By the end of this module, you will understand:
- Why Go was chosen for this project (and why NOT NestJS/Node.js)
- Go's concurrency model: goroutines, channels, select
- Go's standard library idioms for HTTP servers, JSON, database access
- The Go ecosystem for payment systems: sqlc, chi, Watermill, OpenTelemetry
- Error handling patterns, testing, and project structure
- Common Go mistakes and how to avoid them

---

## 1. Why Go, Not NestJS/Node.js?

From Phase 11 (Technology Selection), the decision is **Go 1.22+** for all microservices.

| Concern | Node.js (NestJS) | Go 1.22+ |
|---------|:---:|:---:|
| **GC latency** | 10–100ms pauses (V8) | < 1ms pauses (concurrent GC) |
| **Startup time** | 300ms–3s (V8 warmup) | < 10ms (compiled binary) |
| **CPU-bound throughput** | 1× | 5–10× |
| **Memory per service** | 100MB+ baseline | 10–20MB baseline |
| **Single-binary deploy** | Requires Node runtime | Static binary (scratch image) |
| **Concurrency model** | Event loop (single-threaded) | Goroutines (M:N scheduling) |
| **Type safety** | TypeScript (structural) | Go (structural, simpler) |

### For Payment Systems Specifically:

1. **P99 latency**: Payment flows require sub-250ms total. Go's zero-warmup + sub-ms GC are non-negotiable.
2. **Database-bound concurrency**: A payment service makes many DB calls. Go's goroutines handle 1000s of concurrent DB queries without thread pool tuning.
3. **Operational simplicity**: Single binary means Docker image = `FROM scratch`, no Node Alpine, no `node_modules`, no runtime dependency.
4. **No reflection at runtime**: NestJS depends heavily on decorators and reflection. Go's compile-time code generation (sqlc) avoids this entirely.

---

## 2. Go Concurrency: Goroutines, Channels, Select

### 2.1 Goroutines

A goroutine is a lightweight thread managed by the Go runtime (not the OS). You can start 100,000 goroutines in a single process — try that with OS threads.

```go
// Start a goroutine
go func() {
    result := processPayment(paymentID)
    // handle result
}()

// Or with a named function
go processPayment(paymentID)
```

**In payment systems, goroutines shine for**:
- Concurrent database lookups (check wallet, check fraud, check limits — in parallel)
- Kafka consumer handlers (each partition gets its own goroutine)
- Fan-out notifications (push + email + SMS in parallel)

### 2.2 WaitGroups (Synchronous Coordination)

```go
var wg sync.WaitGroup
errors := make(chan error, 3)

for _, account := range accounts {
    wg.Add(1)
    go func(a Account) {
        defer wg.Done()
        if err := validateAccount(a); err != nil {
            errors <- err
        }
    }(account)
}

wg.Wait()        // Wait for all goroutines
close(errors)    // Close channel

for err := range errors {
    // handle errors
}
```

### 2.3 Channels — Don't Communicate by Sharing Memory

```go
// unbuffered channel — sender blocks until receiver is ready
txnChan := make(chan Transaction)

// buffered channel — 100 items before blocking
txnChan := make(chan Transaction, 100)

// Send
txnChan <- transaction

// Receive
txn := <-txnChan

// Select — wait on multiple channels
select {
case txn := <-txnChan:
    processTransaction(txn)
case err := <-errChan:
    handleError(err)
case <-time.After(5 * time.Second):
    log.Warn("timeout waiting for transaction")
}
```

**Key pattern in payment systems**: Use channels for internal event distribution, NOT for cross-service communication (that's Kafka's job).

### 2.4 Context for Cancellation and Deadlines

Every payment request must have a deadline. Go's `context` package is the standard way to propagate cancellation:

```go
func ProcessPayment(ctx context.Context, req PaymentRequest) (*PaymentResponse, error) {
    // Create a context with timeout — payment must complete in 30s
    ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
    defer cancel()

    // Pass context through all downstream calls
    wallet, err := s.walletService.GetBalance(ctx, req.SenderID)
    if err != nil {
        return nil, fmt.Errorf("get balance: %w", err)
    }

    // Database query with context
    err = s.db.QueryRowContext(ctx, "SELECT ...")
}
```

---

## 3. Go Project Structure (Our Approach)

From Phase 11, our structure follows `go-chi/chi` + `sqlc` pattern:

```
services/
├── wallet-service/
│   ├── cmd/
│   │   └── server/
│   │       └── main.go            // Entry point
│   ├── internal/
│   │   ├── api/
│   │   │   ├── handler.go         // HTTP handlers (thin — validation + response)
│   │   │   ├── middleware.go      // Auth, logging, rate limiting
│   │   │   └── router.go          // Route definitions (chi)
│   │   ├── domain/
│   │   │   ├── wallet.go          // Domain types (structs, interfaces)
│   │   │   └── errors.go          // Domain-specific error types
│   │   ├── service/
│   │   │   └── wallet_service.go  // Business logic (testable, no I/O)
│   │   ├── repository/
│   │   │   ├── postgres.go        // SQL queries (using sqlc generated code)
│   │   │   └── cache.go           // Redis caching layer
│   │   └── events/
│   │       └── publisher.go       // Kafka event publisher
│   ├── db/
│   │   ├── migrations/            // SQL migration files
│   │   └── queries/
│   │       └── wallet.sql         // sqlc input queries
│   ├── Dockerfile
│   └── go.mod
└── payment-service/
    └── ...                        // Same structure
```

**Key rules**:
- `internal/` prevents external packages from importing internal code
- `domain/` has ZERO imports — it defines types and errors only
- `service/` holds all business logic (pure functions, testable)
- `repository/` is the only package that touches DB/Redis
- `api/` is thin — validates request, calls service, marshals response

---

## 4. The Go Ecosystem for This Project

### 4.1 Chi Router

```go
import "github.com/go-chi/chi/v5"

r := chi.NewRouter()

// Middleware stack
r.Use(middleware.RequestID)
r.Use(middleware.Logger)
r.Use(middleware.Recoverer)
r.Use(middleware.Timeout(30 * time.Second))

// Route groups
r.Route("/api/v1/wallets", func(r chi.Router) {
    r.Use(authMiddleware)           // Group middleware
    r.Post("/", walletHandler.Create)
    r.Get("/{id}", walletHandler.GetByID)
    r.Post("/{id}/hold", walletHandler.CreateHold)  // Payment auth hold
})

// Sub-resource routing
r.Route("/api/v1/transactions", func(r chi.Router) {
    r.Post("/", txnHandler.Create) // Idempotent POST with X-Idempotency-Key
    r.Get("/{id}", txnHandler.GetByID)
    r.Post("/{id}/capture", txnHandler.Capture)
    r.Post("/{id}/void", txnHandler.Void)
    r.Post("/{id}/refund", txnHandler.Refund)
})
```

### 4.2 sqlc (Type-Safe Database Code Generation)

This is the opposite of an ORM. You write SQL, and sqlc generates Go functions from it:

```sql
-- db/queries/wallet.sql
-- name: GetWalletByID :one
SELECT * FROM wallets WHERE id = $1 AND deleted_at IS NULL;

-- name: GetWalletBalance :one
SELECT balance FROM wallets WHERE id = $1;

-- name: DeductBalance :exec
UPDATE wallets SET balance = balance - sqlc.arg('amount')
WHERE id = sqlc.arg('wallet_id')
  AND balance >= sqlc.arg('amount')
  AND deleted_at IS NULL
RETURNING balance;

-- name: LockWalletBalance :exec
SELECT id FROM wallets
WHERE id = $1 FOR UPDATE NOWAIT;  -- Pessimistic lock for critical operations
```

Generated Go code:

```go
// Generated by sqlc - DO NOT EDIT.

func (q *Queries) GetWalletByID(ctx context.Context, id uuid.UUID) (Wallet, error) {
    row := q.db.QueryRowContext(ctx, getWalletByID, id)
    var i Wallet
    err := row.Scan(&i.ID, &i.UserID, &i.Balance, ...)
    return i, err
}

func (q *Queries) DeductBalance(ctx context.Context, arg DeductBalanceParams) (int64, error) {
    row := q.db.QueryRowContext(ctx, deductBalance, arg.WalletID, arg.Amount)
    var balance int64
    err := row.Scan(&balance)
    return balance, err
}
```

**Why sqlc over GORM/Ent**:
- Zero runtime overhead (generated code is plain Go)
- You write SQL — no magic query builder
- `SELECT FOR UPDATE NOWAIT` — critical for ledger operations
- Works with PostgreSQL-specific features (JSONB, stored procedures)

### 4.3 Watermill (Event-Driven Messaging)

Watermill is our Go eventing framework (replaces NestJS's `@EventBus()` pattern):

```go
import (
    "github.com/ThreeDotsLabs/watermill"
    "github.com/ThreeDotsLabs/watermill/message"
    "github.com/ThreeDotsLabs/watermill/pubsub/gochannel"
)

// Define event
type PaymentCompletedEvent struct {
    TransactionID string `json:"transaction_id"`
    Amount        int64  `json:"amount"`
    SenderID      string `json:"sender_id"`
    ReceiverID    string `json:"receiver_id"`
    CompletedAt   string `json:"completed_at"`
}

// Publish event
func (s *PaymentService) ProcessPayment(ctx context.Context, req PaymentRequest) error {
    // ... business logic ...

    event := PaymentCompletedEvent{
        TransactionID: txn.ID,
        Amount:        txn.Amount,
        SenderID:      req.SenderID,
        ReceiverID:    req.ReceiverID,
        CompletedAt:   time.Now().UTC().Format(time.RFC3339),
    }

    payload, _ := json.Marshal(event)
    msg := message.NewMessage(watermill.NewUUID(), payload)
    return s.publisher.Publish("payment.completed", msg)
}

// Subscribe handler
handler := func(msg *message.Message) error {
    var event PaymentCompletedEvent
    json.Unmarshal(msg.Payload, &event)

    log.Printf("Payment completed: %s", event.TransactionID)
    // Send notification, update reporting, trigger settlement
    return nil
}
```

For Kafka-backed Watermill (production):

```go
import "github.com/ThreeDotsLabs/watermill-kafka/v2/pkg/kafka"

publisher, err := kafka.NewPublisher(
    kafka.PublisherConfig{
        Brokers:   []string{brokerAddress},
        Marshaler: kafka.DefaultMarshaler{},
    },
    watermill.NewStdLogger(false, false),
)
```

### 4.4 OpenTelemetry Go SDK

Every service auto-instruments:

```go
import (
    "go.opentelemetry.io/otel"
    "go.opentelemetry.io/otel/attribute"
    "go.opentelemetry.io/otel/trace"
)

var tracer = otel.Tracer("payment-service")

func (s *Service) ProcessPayment(ctx context.Context, req PaymentRequest) error {
    ctx, span := tracer.Start(ctx, "payment_service.process_payment",
        trace.WithAttributes(
            attribute.String("transaction_id", req.TransactionID),
            attribute.Int64("amount", req.Amount),
        ),
    )
    defer span.End()

    // All downstream calls automatically propagate context
    wallet, err := s.walletService.GetBalance(ctx, req.SenderID)
    if err != nil {
        span.RecordError(err)
        span.SetStatus(codes.Error, err.Error())
        return err
    }
    // ...
}
```

### 4.5 Testing Patterns

```go
// Unit test — pure logic, no I/O
func TestProcessPayment_InsuffientBalance(t *testing.T) {
    svc := NewPaymentService(mockWalletRepo, mockLedgerRepo)

    req := PaymentRequest{SenderID: "user-1", Amount: 100_000}
    mockWalletRepo.EXPECT().GetBalance(gomock.Any(), "user-1").
        Return(int64(50_000), nil)

    _, err := svc.ProcessPayment(context.Background(), req)
    assert.ErrorIs(t, err, ErrInsufficientBalance)
}

// Integration test — with testcontainers
func TestPostgresJournalEntry(t *testing.T) {
    pg, err := postgres.RunContainer(context.Background(),
        testcontainers.WithImage("postgres:16-alpine"),
    )
    // Run migration, test stored procedure, verify hash chain
}

// Table-driven test
func TestDeductBalance(t *testing.T) {
    tests := []struct {
        name      string
        current   int64
        amount    int64
        wantErr   bool
        wantNew   int64
    }{
        {"sufficient balance", 100_000, 50_000, false, 50_000},
        {"exact balance", 50_000, 50_000, false, 0},
        {"insufficient balance", 30_000, 50_000, true, 30_000},
        {"zero amount", 100_000, 0, true, 100_000},
        {"negative amount", 100_000, -10_000, true, 100_000},
    }

    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            svc := NewService(mockRepo)
            newBal, err := svc.DeductBalance(ctx, tt.amount)
            assert.Equal(t, tt.wantNew, newBal)
        })
    }
}
```

---

## 5. Error Handling in Go

### 5.1 The Idiomatic Way

```go
// Good — wrap errors with context
result, err := doSomething()
if err != nil {
    return fmt.Errorf("do something: %w", err)
}

// Check error exactly
if errors.Is(err, sql.ErrNoRows) {
    return nil, ErrWalletNotFound
}

// Type assertion for custom errors
var domainErr *DomainError
if errors.As(err, &domainErr) {
    log.Printf("Domain error: code=%s message=%s", domainErr.Code, domainErr.Message)
}
```

### 5.2 Domain Errors Pattern

```go
type ErrorCode string

const (
    ErrInsufficientBalance ErrorCode = "INSUFFICIENT_BALANCE"
    ErrWalletFrozen        ErrorCode = "WALLET_FROZEN"
    ErrTransactionNotFound ErrorCode = "TRANSACTION_NOT_FOUND"
    ErrIdempotencyConflict ErrorCode = "IDEMPOTENCY_CONFLICT"
)

type DomainError struct {
    Code    ErrorCode `json:"code"`
    Message string    `json:"message"`
    Detail  string    `json:"detail,omitempty"`
    Err     error     `json:"-"`
}

func (e *DomainError) Error() string { return fmt.Sprintf("%s: %s", e.Code, e.Message) }
func (e *DomainError) Unwrap() error { return e.Err }
```

### 5.3 Panics vs. Errors

In Go, **never use panic for business logic**. Panic is for programmer errors (nil pointer, index out of bounds). All payment errors are returned as errors.

Exception: middleware can `recover()` panics to prevent crashes:

```go
func RecoveryMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        defer func() {
            if rec := recover(); rec != nil {
                log.Error("panic recovered", "error", rec)
                http.Error(w, "internal server error", http.StatusInternalServerError)
            }
        }()
        next.ServeHTTP(w, r)
    })
}
```

---

## 6. Common Go Mistakes (from NestJS background)

| You'll Be Tempted To | Why It Doesn't Work | Go Pattern |
|----------------------|--------------------|------------|
| `@Injectable()` / DI container | No decorators, no reflection | Constructor injection by hand |
| `class PaymentService` | Go has no classes | `type PaymentService struct { ... }` + methods |
| `try/catch` | No exceptions | `if err != nil` everywhere |
| `Promise.all([...])` | No promises | Goroutines + `sync.WaitGroup` |
| `Array.map/filter` | No generics (pre-1.18) | Loops or `slices` package (1.21+) |
| ORM (TypeORM/Prisma) | Hides critical SQL | `sqlc` — you write SQL directly |
| Express middleware chaining | Different model | `chi` middleware (same concept, different API) |
| `process.env` for config | No process global | `os.Getenv()` or Vault at startup |
| Async/Await | No async/await | Go routines are synchronous-looking (no `await`) |

---

## 7. Go Tooling Quick Reference

| Tool | Command | Purpose |
|------|---------|---------|
| **go mod** | `go mod init` / `go mod tidy` | Dependency management |
| **go build** | `go build ./cmd/server` | Compile |
| **go test** | `go test ./... -v -race` | Run all tests with race detector |
| **go vet** | `go vet ./...` | Static analysis |
| **golangci-lint** | `golangci-lint run` | Comprehensive linting |
| **sqlc** | `sqlc generate` | Generate Go code from SQL |
| **goose** | `goose up` | Database migrations |
| **mockgen** | `mockgen -source=...` | Generate mocks for interfaces |

---

## 8. Recommended Learning Path for Go (After This Module)

1. **Tour of Go** (official, ~2 hours): https://go.dev/tour/
2. **Effective Go**: https://go.dev/doc/effective_go
3. **Go by Example**: https://gobyexample.com/
4. **Practical: Write a simple payment endpoint** using chi + sqlc
5. Read this project's Phase 06 and Phase 08 doc for the full API design

---

## Check Questions

1. Why was Go chosen over NestJS for payment services?
2. What's the difference between a goroutine and an OS thread?
3. How does sqlc differ from an ORM like GORM?
4. What package would you use for HTTP routing in this project?
5. How do you handle timeouts for a payment request in Go?
6. What's the idiomatic way to add context to an error in Go?
7. Why is `internal/` used in Go project structure?
8. How do you run all tests with the race detector?

---

## Next Module

[Module 08 — Observability Stack](08-observability-stack.md)

> Go is the hammer. The observability stack tells you where you're hitting.
