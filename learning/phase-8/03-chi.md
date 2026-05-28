# Module 03 — Chi (Go)

## 3.1 Chi Architecture

Chi is a lightweight, idiomatic Go HTTP router. It composes middleware as `func(http.Handler) http.Handler`. The router uses a radix tree for URL matching.

```go
r := chi.NewRouter()

// Middleware (executed in order of r.Use)
r.Use(middleware.RequestID)
r.Use(middleware.RealIP)
r.Use(middleware.Logger)
r.Use(middleware.Recoverer)
r.Use(middleware.Timeout(30 * time.Second))

// Routes
r.Route("/v1/settlement", func(r chi.Router) {
    r.Post("/batch", handler.StartBatch)
    r.Get("/batch/{batchID}", handler.GetBatch)
    r.Get("/merchant/{merchantID}", handler.GetMerchantSettlement)
})
```

## 3.2 Middleware Pattern

```go
// Built-in middleware
r.Use(middleware.RequestID)  // Injects request ID into context
r.Use(middleware.Logger)     // Logs method, path, status, duration
r.Use(middleware.Recoverer)  // Recovers from panics, returns 500

// Custom middleware
func AuthMiddleware(jwtSecret []byte) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
            if token == "" {
                http.Error(w, "missing token", http.StatusUnauthorized); return
            }
            claims, err := validateJWT(token, jwtSecret)
            if err != nil {
                http.Error(w, "invalid token", http.StatusUnauthorized); return
            }
            ctx := context.WithValue(r.Context(), "user", claims)
            next.ServeHTTP(w, r.WithContext(ctx))
        })
    }
}
```

## 3.3 Handler Structure

```go
type SettlementHandler struct {
    db  *sql.DB
}

func (h *SettlementHandler) StartBatch(w http.ResponseWriter, r *http.Request) {
    var req StartBatchRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        http.Error(w, err.Error(), http.StatusBadRequest); return
    }
    if err := validate.Struct(req); err != nil {
        http.Error(w, err.Error(), http.StatusBadRequest); return
    }

    batch, err := h.settlementService.StartBatch(r.Context(), req)
    if err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError); return
    }
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusAccepted)
    json.NewEncoder(w).Encode(batch)
}
```

## 3.4 sqlc — Type-Safe SQL

```sql
-- queries.sql
-- name: GetMerchantSettlement :many
SELECT merchant_id, SUM(amount) as total_amount, COUNT(*) as txn_count
FROM payments
WHERE status = 'COMPLETED' AND created_at >= $1 AND created_at < $2
GROUP BY merchant_id;

-- name: InsertSettlementBatch :one
INSERT INTO settlement_batches (id, period_start, period_end, status, created_at)
VALUES ($1, $2, $3, 'PENDING', NOW()) RETURNING *;
```

```bash
sqlc generate  # Generates Go code from SQL
```

```go
// Generated code (db.go) — type-safe, no ORM
queries := db.New(conn)
settlements, err := queries.GetMerchantSettlement(ctx, db.GetMerchantSettlementParams{
    StartDate: start,
    EndDate:   end,
})
```

## 3.5 Graceful Shutdown

```go
func main() {
    r := chi.NewRouter()
    // ... setup routes

    srv := &http.Server{Addr: ":8080", Handler: r}

    go func() { srv.ListenAndServe() }()

    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
    <-quit

    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()
    srv.Shutdown(ctx)
}
```

## 3.6 Exercises

### Ex 3.1 — REST API with Chi
Build a REST API for settlement batches: `POST /batches`, `GET /batches/{id}`, `GET /batches`. Use sqlc for type-safe SQL. Add RequestID, Logger, and Recoverer middleware.

### Ex 3.2 — Custom Middleware
Write a rate limiting middleware using a token bucket (in-memory). Apply to all `/v1/` routes. Test with concurrent requests.

### Ex 3.3 — Graceful Shutdown
Add graceful shutdown to the settlement service. Simulate a long-running request. Send SIGTERM. Verify the server waits for the request to complete before shutting down.

---

## 3.7 Self-Assessment

- [ ] Can create a Chi router with route groups and middleware
- [ ] Understand middleware composition: `func(http.Handler) http.Handler`
- [ ] Can write sqlc queries and use the generated type-safe Go code
- [ ] Can implement graceful shutdown with signal handling and context
