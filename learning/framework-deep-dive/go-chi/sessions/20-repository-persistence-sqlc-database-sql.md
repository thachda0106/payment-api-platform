# Session 20: Repository & Persistence — database/sql, sqlc, pgx

## Why This Topic Exists

Every Go service that isn't a pure proxy eventually talks to a database. The standard library's `database/sql` package is the foundation — it provides connection pooling, prepared statements, and transaction management that every Go developer relies on. But using it raw leads to boilerplate, reflection-based scanning into structs, and SQL strings scattered across handler code. Without disciplined patterns, the persistence layer becomes the primary source of bugs, performance regressions, and operational incidents.

sqlc flips the ORM paradigm on its head. Instead of generating SQL from your data structures, you write SQL first and sqlc generates fully type-safe Go code from it. The generated types match your SQL schema exactly — no impedance mismatch, no surprise queries, no N+1 problems hidden behind lazy loading. pgx takes this further as a PostgreSQL-specific driver that outperforms the standard `database/sql` interface via the native PG binary protocol, COPY protocol for bulk operations, and support for PostgreSQL-specific features like LISTEN/NOTIFY. Together, these tools form a persistence stack that is fast, predictable, and debuggable — exactly what you want when a production incident is unfolding and you need to understand exactly what queries are running against your database.

The repository pattern is where these tools meet clean architecture. By defining repository interfaces in your domain package and implementing them in a persistence adapter, you decouple business logic from storage details. This means your domain code can be tested with in-memory implementations, your persistence adapter can be swapped (Postgres to MySQL to DynamoDB) without touching business logic, and your integration tests can verify real SQL behavior without spinning up the full application. Staff engineers need to understand this entire stack — from the connection pool's internal free list to the transaction propagation strategy — because when the database is slow at 3 AM, that's where the root cause lives.

## Mental Model

Think of the persistence layer as a pipeline with three stages. Stage 1 is the **connection pool** (`*sql.DB`): it owns a fixed set of TCP connections to PostgreSQL, manages their lifecycle, and hands them to callers on demand. The pool has a free list of idle connections, a counter of in-use connections, and a wait queue when all connections are busy. Understanding the pool's internals — particularly that `SetMaxOpenConns(0)` means unlimited connections — is critical because the default can silently exhaust your database's connection limit under load.

Stage 2 is the **query engine** (sqlc-generated or raw `database/sql`): this translates your application's intent ("get me order 42") into a specific SQL statement with typed parameters, executes it against a connection leased from the pool, and maps the result set into Go structs. sqlc eliminates the manual scanning boilerplate by generating a `Queries` struct with one method per SQL statement, each accepting and returning exactly the types in your schema. No `interface{}`, no `rows.Scan(dest...)` with positional errors, no runtime type mismatches.

Stage 3 is the **repository**, which is just an interface that wraps the query engine with business-meaningful method names. The interface lives in your domain package (`type OrderRepository interface { FindByID(ctx context.Context, id uuid.UUID) (*Order, error) }`), and the implementation lives in a persistence adapter that composes the sqlc `Queries` struct. Domain code calls `orderRepo.FindByID(...)` without knowing whether the data comes from Postgres, an in-memory map, or a gRPC call to another service. This boundary is where you inject read replicas, caching layers, and circuit breakers without changing any business logic.

```
┌─────────────────────────────────────────────────────────────────┐
│                       HTTP Handler                               │
│                         (Chi route)                              │
└─────────────────────────┬───────────────────────────────────────┘
                          │ calls repo.FindByID(ctx, id)
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Repository Interface                          │
│              (domain/orders/repository.go)                       │
│         type Repository interface { FindByID(...) }              │
└─────────────────────────┬───────────────────────────────────────┘
                          │ implemented by
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Persistence Adapter                            │
│              (adapters/postgres/orders_repo.go)                  │
│         composes sqlc.Queries + sql.DB (or *pgxpool.Pool)       │
└─────────────────────────┬───────────────────────────────────────┘
                          │ delegates to
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                   sqlc Generated Code                            │
│              (adapters/postgres/sqlc/orders.sql.go)              │
│         type Queries struct { db DBTX }                          │
│         func (q *Queries) FindOrderByID(ctx, id) (*Order, err)  │
└─────────────────────────┬───────────────────────────────────────┘
                          │ uses
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                 Connection Pool (*sql.DB)                        │
│    ┌──────┐ ┌──────┐ ┌──────┐     ┌──────────────────────┐      │
│    │ conn │ │ conn │ │ conn │ ... │   free list           │      │
│    │ #1   │ │ #2   │ │ #3   │     │   in-use count = N    │      │
│    │(busy)│ │(busy)│ │(idle)│     │   wait_count = W      │      │
│    └──────┘ └──────┘ └──────┘     │   max_open = 25       │      │
│                                    └──────────────────────┘      │
└─────────────────────────┬───────────────────────────────────────┘
                          │ TCP connection
                          ▼
                    ┌──────────┐
                    │PostgreSQL│
                    └──────────┘
```

## Internal Architecture

**sql.DB is not a connection — it is a pool.** The `sql.DB` struct in `database/sql/sql.go` contains a `connector` (the driver-specific implementation, e.g., pgx via `stdlib.RegisterConnConfig`), a `mu sync.Mutex` protecting pool state, `freeConn []*driverConn` (the free list of idle connections), `numOpen int` (total connections created, including idle + in-use), `maxOpen int` (the limit), `maxIdleCount int`, and `maxLifetime` / `maxIdleTime time.Duration`. When you call `db.QueryContext(ctx, ...)`, the pool first checks the free list. If empty and `numOpen < maxOpen`, it opens a new connection. If at capacity, it blocks (with context cancellation support) until a connection is returned. The return path puts the connection back on the free list if idle, or closes it if broken. This design is what makes `sql.DB` safe for concurrent use by many goroutines — you create one `*sql.DB` and share it across your entire application.

**The default MaxOpenConns is 0 — unlimited.** This is the single most dangerous default in `database/sql`. A service under load will keep opening connections until it hits PostgreSQL's `max_connections` limit (default 100), at which point new connections fail — or worse, connection storms cause cascading failures across services. Always call `db.SetMaxOpenConns(N)` where N is derived from your PostgreSQL max_connections divided by the number of service instances, minus a buffer. For a Go service with 4 replicas and a database with `max_connections = 100`, set `MaxOpenConns = 20` (100/4 = 25, minus 5 for admin/pgbouncer).

**sqlc's code generation model.** sqlc reads a `sqlc.yaml` config file, discovers `*.sql` files in the configured directory, parses them, and generates type-safe Go code. Each SQL query file contains named queries with a `-- name: FunctionName :one` or `:many` or `:exec` annotation. For `:one` (returns exactly one row), sqlc generates a method returning `(*Row, error)`. For `:many`, a method returning `([]Row, error)`. For `:exec`, a method returning `(sql.Result, error)`. The generated types — `Row`, `CreateRowParams`, `UpdateRowParams` — are structs whose fields directly correspond to your SQL column types. A `TEXT NOT NULL` column becomes `string`, a `TIMESTAMPTZ` becomes `time.Time`, and a nullable `INTEGER` becomes `sql.NullInt32`. This is the opposite of an ORM: you write the SQL, and the Go types are derived from it, not the other way around.

**The sqlc DBTX interface enables composition.** sqlc generates a `DBTX` interface with `ExecContext`, `QueryContext`, and `QueryRowContext` methods — which both `*sql.DB` and `*sql.Tx` satisfy. This means the same `Queries` struct can be used with a pool for non-transactional work, and with a transaction for atomic operations. A common pattern is to create a concrete `Repository` struct that holds a `*Queries` and a `*sql.DB`, then implement transactional methods by calling `db.BeginTx(ctx, nil)`, creating a new `Queries` with the transaction, and passing it to the query methods:

```go
func (r *Repository) CreateOrder(ctx context.Context, params CreateOrderParams) error {
    tx, err := r.db.BeginTx(ctx, nil)
    if err != nil {
        return fmt.Errorf("begin tx: %w", err)
    }
    defer tx.Rollback() // no-op after Commit

    q := r.Queries.WithTx(tx)
    if _, err := q.InsertOrder(ctx, params); err != nil {
        return fmt.Errorf("insert order: %w", err)
    }
    if _, err := q.InsertOrderItems(ctx, items); err != nil {
        return fmt.Errorf("insert items: %w", err)
    }
    return tx.Commit()
}
```

**pgx replaces database/sql wholesale for maximum performance.** The `jackc/pgx` driver supports both a standard `database/sql` interface (via `pgx/v5/stdlib`) and its own native `pgxpool.Pool` type. The native pool outperforms `sql.DB` + `pgx/stdlib` because it skips the `database/sql` abstraction layer entirely: no interface calls, no extra allocations for argument transformation, and direct access to the PostgreSQL binary protocol. For services where database performance is the bottleneck, using `pgxpool.Pool` directly with sqlc's `pgx` driver (configured via `sqlc.yaml` `sql_package: "pgx/v5"`) can yield 15-30% throughput improvements. The tradeoff is that you lose the widest ecosystem of database/sql-compatible tooling (tracing middlewares, stats exporters).

**Connection lifecycle events.** Every connection goes through: `driver.Connector.Connect(ctx)` → authentication → `USE` session setup → pooled in free list → `Conn(ctx)` leases it to a caller → query execution → `PutConn()` returns it to pool → eventually `Close()` due to MaxConnLifetime or idle timeout. The `driver.Conn` interface exposes `PrepareContext` for statement caching: the pool transparently caches prepared statements on the server side, and subsequent calls to the same query reuse the prepared statement plan without re-parsing. This is automatic in database/sql — you don't need to call `db.Prepare()` explicitly; `db.QueryContext` does it internally and caches the result.

**Schema migrations with golang-migrate.** The `golang-migrate/migrate` tool applies numbered SQL migration files (`000001_create_orders.up.sql`, `000001_create_orders.down.sql`) in sequence. Each migration runs in a transaction. The tool uses a `schema_migrations` table to track which version has been applied, and a filesystem lock to prevent concurrent migration runs. In CI/CD, migrations run as an init container or job before the application starts. The `migrate.Up()` call is idempotent — if all migrations are already applied, it returns `migrate.ErrNoChange`. For zero-downtime deployments, migrations must be backward-compatible: add columns with defaults `NULL` or a default value, never rename or drop columns that the current code uses, and apply the migration before deploying the new code that references the new columns.

## Runtime Behavior

When a request arrives at a Chi handler that calls `repo.FindByID(ctx, id)`, the runtime behavior depends on pool state. If a free connection exists, the goroutine takes it from the free list (acquiring `sql.DB.mu`, popping from `freeConn`), wraps it with statement caching metadata, and runs the query. If no free connection exists but `numOpen < maxOpen`, a new TCP connection is opened — this involves DNS resolution (or PgBouncer port lookup), TCP handshake, TLS negotiation, PostgreSQL authentication (SCRAM-SHA-256), and `USE database` / `SET application_name` statements. Opening a new connection takes 2-10ms depending on network latency and CPU. If `numOpen >= maxOpen`, the goroutine blocks on a channel receive (`connRequests` channel of capacity `maxOpen + 1`), waiting for a connection to be returned. The `context.Context` passed to `QueryContext` is propagated: if the context is cancelled or times out while waiting, the goroutine unblocks with `ctx.Err()` rather than hanging indefinitely.

For a sqlc-generated `:many` query, the runtime does: `rows, err := q.db.QueryContext(ctx, "-- name: ListOrders :many\nSELECT id, status, amount FROM orders WHERE status = $1", params.Status)`. Under the hood, `database/sql` checks the statement cache, sends the query text to PostgreSQL (or uses a prepared statement ID if cached), PostgreSQL plans and executes the query, and streams result rows back over the TCP connection. Each call to `rows.Next()` reads the next row from the network buffer. sqlc's generated `rows.Close()` is deferred immediately after opening — this is critical because failing to close rows leaves the connection in a dirty state and prevents it from returning to the pool. The scan code generated by sqlc uses `rows.Scan(&i.ID, &i.Status, &i.Amount)` with the exact types and column ordering from your SQL schema.

Transaction isolation comes from PostgreSQL. `db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})` starts a SERIALIZABLE transaction. In practice, most Go services use READ COMMITTED (the default) and handle write skew with application-level checks like `SELECT ... FOR UPDATE` or optimistic concurrency via version columns. The `defer tx.Rollback()` pattern is idiomatic: if `tx.Commit()` succeeds, the deferred `Rollback()` is a no-op; if any error causes a return before the commit, the deferred rollback ensures the transaction is cleaned up and its connection is returned to the pool intact.

Connection pool exhaustion manifests as increasing latency followed by context deadline exceeded errors. When `wait_count` in `db.Stats()` is non-zero, goroutines are queued waiting for connections. This typically happens because: (1) a goroutine started a transaction and never closed it (connection leak — the connection is held until `tx.Rollback()` or garbage collection), (2) long-running queries hold connections for seconds instead of milliseconds, or (3) `maxOpenConns` is set too low for the concurrency level. The fix depends on the root cause: use `pprof` goroutine profile to find leaked transactions, add query timeouts via `context.WithTimeout`, or increase `maxOpenConns` while ensuring PostgreSQL can handle the total across all replicas.

## Flow Diagrams

```
REQUEST LIFETIME: Handler → Repository → sqlc → sql.DB → PostgreSQL

  Chi Router
     │
     ▼
  handler.ServeHTTP(w, r)
     │
     ├─► ctx := r.Context()
     │
     ├─► order, err := repo.FindByID(ctx, id)
     │       │
     │       ▼
     │   PostgresOrderRepo.FindByID(ctx, id)
     │       │
     │       ▼
     │   q.FindOrderByID(ctx, id)         ← sqlc generated
     │       │
     │       ▼
     │   q.db.QueryRowContext(ctx,        ← DBTX interface (sql.DB or sql.Tx)
     │       "SELECT id, status, amount
     │        FROM orders WHERE id = $1",
     │        id).Scan(&o.ID, &o.Status, &o.Amount)
     │       │
     │       ▼
     │   database/sql/sql.go: QueryRowContext()
     │       │
     │       ├─► db.conn(ctx, strategy)     ← acquire connection
     │       │       │
     │       │       ├─► freeConn list? ──► pop and return
     │       │       ├─► numOpen < maxOpen? ──► open new conn
     │       │       └─► else ──► block on connRequests channel
     │       │                       │
     │       │                       └─► wait until connection returned
     │       │                            OR ctx cancelled/timed out
     │       │
     │       ├─► dc.ci.Query(query, args)   ← driver.Conn.QueryContext
     │       │       │
     │       │       ▼
     │       │   pgx Conn.QueryContext()     ← send Parse → Bind → Execute
     │       │       │                        ← or use cached prepared statement
     │       │       ▼
     │       │   PostgreSQL backend           ← plan, execute, return rows
     │       │
     │       ├─► rows.Scan(dest...)           ← scan column values into Go types
     │       │
     │       └─► dc.putConn(err)              ← return connection to pool
     │               │
     │               ├─► err != nil? ──► close connection
     │               └─► err == nil? ──► push to freeConn list
     │
     ├─► respondWithJSON(w, order)
     │
     └─► [request completes, goroutine released]


TRANSACTION FLOW WITH sqlc + sql.Tx:

  repo.CreateOrder(ctx, params)
     │
     ├─► tx, _ := db.BeginTx(ctx, nil)
     │       │
     │       ▼
     │   BEGIN ← sent to PostgreSQL
     │   connection marked "in transaction"
     │       │
     ├─► defer tx.Rollback()
     │       │
     ├─► q := repo.Queries.WithTx(tx)
     │       │
     ├─► q.InsertOrder(ctx, params)
     │       │
     │       ▼
     │   INSERT INTO orders ... VALUES ($1, $2, $3)
     │       │
     │   [rows affected = 1]
     │       │
     ├─► q.InsertOrderItems(ctx, items)   ← runs on same connection
     │       │
     │       ▼
     │   INSERT INTO order_items ... VALUES ($1, $2), ($3, $4), ...
     │       │
     │   [rows affected = 3]
     │       │
     ├─► err := tx.Commit()
     │       │
     │       ▼
     │   COMMIT ← sent to PostgreSQL
     │   connection unmarked "in transaction"
     │   connection returned to pool
     │       │
     └─► [defer tx.Rollback()]  → no-op (connection already committed)
     │       │
     └─► return err


CURSOR PAGINATION FLOW:

  Client: GET /api/orders?cursor=550e8400-e29b&limit=50
     │
     ▼
  Handler parses cursor (base64 → UUID) and limit
     │
     ▼
  repo.ListOrders(ctx, cursor, limit)
     │
     ▼
  q.ListOrdersAfterID(ctx, ListOrdersAfterIDParams{
      Cursor: cursor,       ← uuid.UUID
      Limit:  int32(51),    ← fetch one extra to detect hasMore
  })
     │
     ▼
  SELECT id, status, amount
  FROM orders
  WHERE id > $1              ← keyset: use primary key
  ORDER BY id ASC
  LIMIT $2
     │
     ▼
  Results: [51 rows]
     │
     ▼
  hasMore := len(rows) > limit
  if hasMore { rows = rows[:limit] }
     │
     ▼
  Response: {
      "data":    [50 orders],
      "cursor":  "base64(last_order_id)",
      "hasMore": true
  }
```

## Source Code Reading Guide

**Core database/sql**: Start with `database/sql/sql.go`. The `DB` struct definition (around line 350-420) shows all pool state fields. The `conn` method (around line 1100) is the connection acquisition logic — read this carefully; it's only ~100 lines but explains the entire pool behavior. The `putConn` method shows how connections return to the pool. `QueryContext` and `ExecContext` show the query execution path. Skip the deprecated methods (`Query`, `Exec` without context). The `Tx` struct shows how transactions implement `DBTX`.

**pgx pool**: `jackc/pgx/v5/pgxpool/pool.go` — the `Pool` struct mirrors `sql.DB` but uses pgx-native connections. `Acquire()` and `Release()` replace `conn()` and `putConn()`. The pool uses a `sync.Cond` for signaling and a semaphore (`chan struct{}`) for limiting connections — compare this with `database/sql`'s channel-based approach.

**sqlc generated code patterns**: Create a sample project with sqlc and read the generated `*.sql.go` files. Key patterns: the `Queries` struct with `db DBTX` field, the `WithTx(tx *sql.Tx)` method that returns a new `Queries` wrapping the transaction, the `:one`, `:many`, `:exec` generated methods, and the `models.go` file with generated struct types. Pay attention to how nullable types are handled: `sql.NullString`, `sql.NullInt64`, `sql.NullTime` vs. `pgtype.Text`, `pgtype.Int8`, `pgtype.Timestamptz` when using the pgx driver.

**golang-migrate**: `golang-migrate/migrate/v4/migrate.go` — the `Migrate` struct, `Up()` and `Down()` methods. `database/postgres/postgres.go` — the PostgreSQL driver implementation. `source/file/file.go` — how migration files are discovered and ordered. Skip the CLI code unless you're debugging migration tooling itself.

**Reading order**: 1. `database/sql/sql.go` (DB struct + conn() + putConn()) → 2. Your sqlc `queries/` directory (understand generated code) → 3. Your repository implementation (understand how you compose these) → 4. `pgxpool/pool.go` (if using pgx native) → 5. `golang-migrate/migrate.go` (if curious about migration internals).

**What to skip**: The wire protocol implementation in pgx (`pgproto3/`), the SQL parser in sqlc (built on ANTLR — massive codebase), the golang-migrate CLI entry point, and any `database/sql` driver implementations other than pgx.

## Production Failure Scenarios

**Scenario 1: Connection pool exhaustion under load spike.** A flash sale doubles traffic. All 25 connections are in use, `wait_count` hits 500, request latency spikes from 5ms to 30s (context timeout). Root cause: `MaxOpenConns = 25` but the database can handle 200. Fix: increase `MaxOpenConns` to 75 (leaving 125 for other services). But the deeper issue: each request held a connection for 200ms due to a slow third-party API call inside the transaction, blocking others. Move the API call outside the transaction, reducing per-request connection hold time to 5ms, which means 25 connections can serve 5000 req/s instead of 125.

**Scenario 2: Missing `rows.Close()` causes connection leak.** After deploying a new feature, the service gradually slows down over 2 hours then becomes completely unresponsive. `db.Stats()` shows `InUse = 25`, `OpenConnections = 25`, `WaitCount = 3000`. A goroutine dump (`pprof.Lookup("goroutine").WriteTo(...)`) shows 25 goroutines blocked in PostgreSQL `read()` — but no corresponding queries running. Root cause: a new handler forgot `defer rows.Close()`, so every query left a connection in a dirty state where it was returned to the pool but couldn't be reused. The fix is immediate (add the defer), but the real lesson is: code review must catch missing `defer rows.Close()` — or better, use sqlc which generates it automatically.

**Scenario 3: `MaxOpenConns = 0` (default) exhausts PostgreSQL.** A new service deploys with 8 replicas, each using the default `MaxOpenConns = 0`. During normal load, each replica opens 15 connections (120 total). Under peak load, they spike to 50 each (400 total). PostgreSQL's `max_connections = 100` is exceeded, and connections start failing with `FATAL: sorry, too many clients already`. All 8 replicas simultaneously lose their connections and crash-loop trying to reconnect. The on-call engineer can't even connect via `psql` because the connection slots are all taken. Fix: set `MaxOpenConns = 10` per replica, add connection rate limiting to prevent thundering herd on restart, and configure PgBouncer in transaction-pooling mode in front of PostgreSQL.

## Debugging Techniques

**Technique 1: Inspect pool state with `db.Stats()`.** Add a debug endpoint that returns `db.Stats()` as JSON (behind authentication or only in non-production environments). This exposes: `MaxOpenConnections`, `OpenConnections` (total), `InUse` (currently held), `Idle` (in free list), `WaitCount` (cumulative goroutines that had to wait), `WaitDuration` (cumulative wait time), `MaxIdleClosed` (connections closed due to idle timeout), `MaxLifetimeClosed` (connections closed due to age). A steadily increasing `WaitCount` and `WaitDuration` means pool exhaustion. A gap between `OpenConnections` and `InUse + Idle` means connections are stuck in an intermediate state (possible leak).

```go
func poolStatsHandler(db *sql.DB) http.HandlerFunc {
    return func(w http.ResponseWriter, r *http.Request) {
        stats := db.Stats()
        json.NewEncoder(w).Encode(map[string]interface{}{
            "max_open":     stats.MaxOpenConnections,
            "open":         stats.OpenConnections,
            "in_use":       stats.InUse,
            "idle":         stats.Idle,
            "wait_count":   stats.WaitCount,
            "wait_duraion": stats.WaitDuration.String(),
            "max_idle_closed":   stats.MaxIdleClosed,
            "max_lifetime_closed": stats.MaxLifetimeClosed,
        })
    }
}
```

**Technique 2: Find connection-leaking queries with goroutine profiles.** When `WaitCount` is high but `Idle + InUse == OpenConnections`, connections are not being returned. Collect a goroutine profile: `curl http://localhost:6060/debug/pprof/goroutine?debug=2`. Search for goroutines blocked in `database/sql.(*DB).conn` — these are waiting for a connection. Search for goroutines in `net.(*netFD).Read` or `net.(*netFD).Write` with `database/sql` in the call stack — these are holding connections with open queries. The `debug=2` output shows full goroutine stacks including the line of application code that acquired the connection. Cross-reference with `pg_stat_activity` on the database to see which queries are running.

**Technique 3: Profile query latency with pgx tracing.** pgx supports a `Tracer` interface with callbacks for `TraceQueryStart`, `TraceQueryEnd`. Implement it to log slow queries:

```go
type slowQueryLogger struct{}

func (l *slowQueryLogger) TraceQueryEnd(ctx context.Context, conn *pgx.Conn, data pgx.TraceQueryEndData) {
    if data.Err != nil || data.CommandTag.RowsAffected() == 0 {
        return
    }
    slog.WarnContext(ctx, "slow query",
        "duration", time.Since(connStartTime(ctx)),
        "sql", data.SQL,
    )
}
```

For nondestructive profiling, enable `auto_explain` in PostgreSQL with `auto_explain.log_min_duration = 100ms` and `auto_explain.log_analyze = on` — this logs the execution plan for any query taking >100ms, including actual vs. estimated row counts.

## Observability Considerations

**Log**: Every repository method should log at DEBUG level: `slog.DebugContext(ctx, "query", "method", "FindOrderByID", "duration_ms", d.Milliseconds())`. At WARN level, log when pool capacity is strained: `WaitCount - previous_WaitCount > 0` in a periodic check (every 30s). At ERROR level, log query failures with the SQL error code (`pq: duplicate key value violates unique constraint` → 23505). Include the `request_id` from Chi middleware in all log lines for correlation. Never log full SQL query text with parameter values unless in a development environment — it can leak PII.

**Metrics**: Export these via Prometheus: `db_pool_connections{state="in_use|idle|open"}` (gauge), `db_pool_wait_duration_seconds` (histogram — how long goroutines wait for connections), `db_query_duration_seconds` (histogram — how long queries take to execute), `db_query_errors_total{error_type="constraint|timeout|connection|other"}` (counter), `db_transactions_total{result="commit|rollback"}` (counter), `db_migrations_applied` (gauge — database version). The `db_query_duration_seconds` histogram buckets should be: `[0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]` — this gives good resolution at the low end and covers outliers up to 10 seconds.

**Traces**: Every repository call should create a span: `ctx, span := tracer.Start(ctx, "PostgresRepo.FindOrderByID")`. Set attributes: `db.system = "postgresql"`, `db.operation = "SELECT"`, `db.sql.table = "orders"`, `db.statement` (the SQL text, parameterized), `db.query.params.id = $1` (the ID value). For transactions, create a parent span "Transaction" that wraps all query spans. This lets you see the entire transaction's duration and which individual queries within it are slow.

**Alerts**: Alert on: `db_pool_wait_count` increase rate > 0.1/s (connection pool stress), `db_query_errors_total` increase rate > 1/s (database errors), `db_query_duration_seconds` p99 > 1s (slow queries), `db_pool_connections{state="in_use"} / db_pool_connections{state="open"} > 0.9` (pool near exhaustion). Use multi-window burn-rate alerts: if error budget burn rate exceeds 14.4x in a 1-hour window AND 6x in a 5-minute window, page the on-call.

## Performance Implications

**Connection pool sizing.** More connections do not mean more throughput. PostgreSQL uses one backend process per connection, and context-switching between 100+ backends degrades total throughput. The PostgreSQL Wiki recommends `connections = ((core_count * 2) + effective_spindle_count)` as a starting point for the total number of active connections. For a typical cloud database with 8 vCPUs and NVMe storage: `(8 * 2) + 1 = 17` active connections max. A connection pooler like PgBouncer multiplexes hundreds of client connections onto a few server connections, letting you set `MaxOpenConns = 500` per replica safely because PgBouncer serializes them to 20 actual PostgreSQL connections.

**pgx vs. database/sql performance.** pgx's native `pgxpool.Pool` avoids the `database/sql` abstraction overhead: no `[]interface{}` argument conversion (pgx passes typed arguments directly to the wire protocol), no `driver.Value` → Go type round-trip (pgx scans directly from the binary wire representation), and no mutex contention on `sql.DB` (pgx pool uses lock-free data structures where possible). Benchmarks show pgx-pool is 15-30% faster than `sql.DB + pgx/stdlib` for read-heavy workloads. The COPY protocol in pgx (`conn.CopyFrom(...)`) for bulk inserts is 3-5x faster than batched INSERT statements because it uses PostgreSQL's COPY stream protocol instead of individual statement round-trips.

**N+1 query elimination.** The N+1 pattern in Go typically looks like this: loop over N items, call `repo.GetRelated(ctx, item.ID)` for each. This produces N database round-trips. The fix is the DataLoader pattern (from `graph-gophers/dataloader`): collect all IDs from the loop, issue a single batch query `SELECT * FROM related WHERE parent_id = ANY($1)`, then distribute results back to callers. A DataLoader implementation in Go collects keys for a cycle (typically one HTTP request), then flushes all batched queries at once. This turns N queries into 1 query, reducing latency from `N * RTT` to `1 * RTT`.

**Optimistic concurrency vs. SELECT FOR UPDATE.** `SELECT ... FOR UPDATE` locks the selected rows until the transaction commits, blocking all other writers. For high-contention tables, this serializes all writes and creates a queue. Optimistic concurrency uses a `version` column: `UPDATE orders SET status = $1, version = version + 1 WHERE id = $2 AND version = $3 RETURNING version`. If `RowsAffected() == 0`, another transaction modified the row — retry the operation. This avoids locks entirely at the cost of potential retries. For tables with low write contention (<10% of requests conflict), optimistic concurrency outperforms pessimistic locking by 2-5x.

## Architecture Implications

The repository pattern creates a seam that enables architectural decisions without code changes in the domain layer. When you need read replicas, you create a `ReadReplicaOrderRepo` that wraps two sqlc `Queries` instances — one pointing to the writer, one pointing to the reader — and dispatch `FindByID` to the reader while routing `Create` to the writer. The domain code sees only `OrderRepository` and is unaware of the routing.

The `DBTX` interface in sqlc (or a custom `Querier` interface generated by sqlc with `emit_interface: true`) is the single most important architectural decision in the persistence layer. By depending on `DBTX` instead of a concrete `*sql.DB` or `*pgxpool.Pool`, your repository implementation becomes testable: you can inject a `*sql.Tx` in integration tests (for rollback-after-test pattern), or a mock `DBTX` in unit tests. This also enables adding cross-cutting concerns — logging, metrics, tracing — by wrapping the `DBTX` interface with a decorated implementation rather than modifying every repository method.

For multi-tenancy, the repository pattern lets you inject a tenant-aware connection pool that selects a database or schema based on the tenant ID in the context. Each tenant gets an isolated connection pool (with its own `MaxOpenConns` limits), and the repository delegates to the appropriate pool without the domain code knowing about multi-tenancy. This is simpler and safer than row-level security (RLS) patterns in PostgreSQL, though it does require per-tenant connection management.

The migration strategy needs to align with your deployment model. If you deploy with Kubernetes, run migrations as an init container that blocks application startup until migrations complete. If you run on VMs, have the application call `migrate.Up()` on startup with a retry loop. For zero-downtime deployments, the rule is: migrations must be backward-compatible with the current running code, and the new code must handle both old and new schema shapes. This means adding NOT NULL columns requires a multi-step migration: (1) add column with DEFAULT, (2) backfill existing rows, (3) add NOT NULL constraint, (4) deploy new code.

## Team Ownership Implications

The persistence layer should be owned by a data platform team or a backend infrastructure team, not by individual feature teams. The reason: connection pool configuration, driver selection, migration tooling, and database-level monitoring affect every service. If each team independently chooses pgx vs. database/sql, configures `MaxOpenConns` differently, and writes their own repository pattern, you get fragmentation that makes on-call rotations impossible. The platform team provides a Go module (`pkg/persistence`) that exports `NewPool(cfg Config) (*sql.DB, error)` with validated defaults, a `Repository[T any]` generic interface, and pre-configured sqlc scaffolding.

Feature teams own their SQL queries and migration files within their service boundaries. The data platform team reviews SQL for performance and correctness (indexes needed, migration compatibility, query plans) but does not write business-logic queries. This split means feature teams can move fast on their domain, while the platform team ensures operational safety: no team sets `MaxOpenConns = 0`, no team forgets `defer rows.Close()`, no team accidentally uses OFFSET pagination on large tables, and all teams export consistent observability signals.

## Interview Questions

**Q1: What is the default value of `MaxOpenConns` in `database/sql`, and why is it dangerous?**
Answer: The default is 0, meaning unlimited connections. Under load, the pool will open connections without bound until it hits PostgreSQL's `max_connections` limit or OOM-kills the client process. This causes cascading failures: when PostgreSQL reaches its connection limit, new connections are rejected, all replicas see `FATAL: too many clients`, and they can't reconnect because the existing connections from dead replicas haven't been cleaned up yet. The fix is explicit: `db.SetMaxOpenConns(N)` where N accounts for the number of replicas, PgBouncer limits, and a safety buffer. The formula: `N = (max_connections - reserve_for_admin) / num_replicas`. For a 4-replica service with `max_connections = 100` and admin reserve of 20: `N = (100 - 20) / 4 = 20`.

**Q2: How does sqlc handle NULL values differently when using the pgx driver vs. the standard database/sql driver?**
Answer: With the standard `database/sql` driver, sqlc generates nullable types using Go's `sql.NullString`, `sql.NullInt64`, `sql.NullTime`, etc. These are structs with a `Valid bool` and the value. With the pgx driver (`sql_package: "pgx/v5"` in sqlc.yaml), sqlc uses pgx's own nullable types: `pgtype.Text`, `pgtype.Int8`, `pgtype.Timestamptz`. The pgx types support the binary protocol natively (no string conversion) and implement the `database/sql.Scanner` and `driver.Valuer` interfaces for compatibility. The key difference: `sql.NullString` requires checking `.Valid` before accessing `.String`; `pgtype.Text` has a `Status` field (Present/Null/Undefined) and a `String` field. Mixing them (using pgx pool but sqlc configured for database/sql) causes type errors at the generated code level.

**Q3: How do you pass a transaction through layers without putting it in context?**
Answer: There are three strategies. (1) **Explicit parameter**: every method accepts a `Querier` interface (or `*sqlc.Queries` wrapping a `DBTX`) — callers pass the transaction-wrapped querier. This is the most explicit and testable approach. (2) **Context-based**: store the transaction in the context using `context.WithValue`. The repository extracts it: `tx, ok := ctx.Value(txKey).(*sql.Tx)`. If present, use it; otherwise use the pool. This is convenient but hides the transaction dependency, making it impossible to see from the function signature that a transaction is required. (3) **Transaction manager**: a `TxManager` struct exposes `WithinTx(ctx, func(ctx context.Context) error)` — it stores the transaction in context internally, and repositories always extract from context. The choice depends on team preference, but the explicit parameter approach (1) has the least magic and is recommended for services with complex transactional boundaries.

**Q4: Why is `WHERE id > $1 ORDER BY id LIMIT $2` (keyset pagination) preferred over `OFFSET $1 LIMIT $2` for API pagination?**
Answer: OFFSET-based pagination has two fatal problems. (1) **Performance**: `OFFSET 10000 LIMIT 50` still scans rows 1-10000 and discards them — PostgreSQL's planner can't optimize this away. The scan cost grows linearly with the offset. (2) **Correctness**: if rows are inserted or deleted between page fetches, the same row appears on multiple pages or is skipped entirely (the "phantom read" problem for pagination). Keyset pagination (`WHERE id > last_seen_id`) avoids both: performance is O(log N) due to the B-tree index on `id`, and correctness is guaranteed because the cursor position is stable — new inserts with higher IDs appear on subsequent pages, and inserts with lower IDs are already past. The tradeoff: keyset pagination doesn't support jumping to arbitrary pages (no "page 5 of 20"), but for infinite-scroll APIs, that's acceptable.

**Q5: How do you handle the situation where a transaction commits successfully but the application doesn't receive the commit confirmation due to a network partition?**
Answer: This is the "at-most-once vs. at-least-once" delivery problem. If the COMMIT message was received by PostgreSQL but the TCP ACK was lost, the client sees a network error, calls `tx.Rollback()`, and returns an error to the caller. The caller retries the request, which creates a duplicate. Solution: make every mutation idempotent. For inserts, use a client-generated UUID (not a SERIAL/BIGSERIAL) as the primary key, so re-insertion hits a unique constraint violation `23505` and can be treated as success. For updates, use a version column: `UPDATE ... WHERE id = $1 AND version = $2`. If the previous COMMIT succeeded, the version has changed, so the retried update matches zero rows, and you can detect this and treat it as success. For operations that can't be made idempotent, use an outbox pattern: insert an outbox message in the same transaction as the mutation, and have a separate process deliver messages with exactly-once semantics using a deduplication key.

## Hands-On Exercises

**Exercise 1: Implement a keyset-paginated list endpoint with sqlc.** Create a `queries/orders.sql` file with a `ListOrdersAfterID` query. Create a `PostgresOrderRepo` that wraps sqlc's `Queries`. Implement a handler that parses a base64-encoded cursor query parameter, calls the repository, and returns JSON with `data`, `cursor`, `hasMore`. Write a benchmark that creates 10,000 orders and measures the p99 latency of the list endpoint at cursor positions 0, 5000, and 9999. Verify that latency is <5ms regardless of cursor position (proving O(log N) performance). Compare with an OFFSET-based implementation and show the latency degradation at `OFFSET 10000`.

**Exercise 2: Reproduce and diagnose a connection pool exhaustion incident.** Write a handler that acquires a transaction and sleeps for 1 second before committing. Start with `MaxOpenConns = 5`. Use `vegeta` or `hey` to send 50 concurrent requests. Observe `db.Stats()` — `WaitCount` should grow. Reduce the sleep to 10ms and confirm `WaitCount` drops to 0. Add a "/debug/pool" endpoint exposing `db.Stats()` as JSON. Write a Prometheus alert rule that fires when `rate(wait_count[5m]) > 0.1`. Write a goroutine dump script that saves the goroutine profile when the alert fires.

**Exercise 3: Add optimistic concurrency to an update endpoint.** Create an `orders` table with a `version INTEGER NOT NULL DEFAULT 1` column. Modify the update query to use `WHERE id = $1 AND version = $2` and `RETURNING version`. If `RowsAffected() == 0`, read the current version from the database, construct an error (`ErrVersionConflict{CurrentVersion: current}`), and return HTTP 409 Conflict to the client. Implement a retry loop in the handler that detects 409 and retries up to 3 times with a FIFO delay. Benchmark with 100 concurrent goroutines updating the same row — compare throughput with and without optimistic concurrency (vs. SELECT FOR UPDATE).

## Advanced Challenges

**Challenge 1: Implement a DataLoader that batches queries within a single HTTP request and integrates with Chi middleware.** The DataLoader should: (a) use `context.Context` to scope batching to the current request, (b) collect IDs from multiple goroutines within the same request, (c) flush all batches automatically when the handler returns, (d) support configurable max batch size and batch window, (e) handle cache misses (items not found in the batch result) by calling a fallback single-load function. The middleware should inject the DataLoader into the context. Measure N+1 query count before and after DataLoader integration using `pg_stat_statements` — a handler that previously made 101 queries (1 list + 100 individual lookups) should now make 2 queries (1 list + 1 batch).

**Challenge 2: Build a connection pool that supports read/write splitting, circuit breaking, and health checking with automatic failover.** Requirements: (a) Maintain separate pools for writer and reader connections, (b) Route `SELECT` queries to read pool, `INSERT/UPDATE/DELETE` to write pool, (c) Implement a circuit breaker: if the write pool has >50% error rate over 30s, stop routing writes and return HTTP 503, (d) Health-check each pool every 10 seconds with `SELECT 1` — if a pool fails 3 consecutive checks, mark it unhealthy and drain connections, (e) When the write pool becomes unhealthy and there's a promoted read replica, promote it to writer (in a managed database, detect promotion via DNS change or connection string swap), (f) Export pool per-pool metrics (in_use, idle, error_count, circuit_state) to Prometheus. This challenge requires understanding `database/sql` internals deeply enough to wrap `driver.Connector` with custom routing logic.

## Key Insights

- `database/sql`'s default `MaxOpenConns = 0` (unlimited) is a production timebomb — always set it explicitly, and derive the value from database capacity divided by replica count minus a safety buffer
- sqlc inverts the ORM relationship: you write SQL as the source of truth, and type-safe Go code is generated from it — this eliminates the impedance mismatch and surprise queries that plague active-record ORMs
- The `defer rows.Close()` pattern is non-negotiable — a single missing `Close()` poisons the connection pool; sqlc generates this automatically, which alone justifies using it over raw `database/sql`
- `defer tx.Rollback()` is safe and idiomatic because it becomes a no-op after `tx.Commit()` — this pattern means you can use early returns for errors without worrying about leaving an open transaction
- Keyset pagination (`WHERE id > $1 ORDER BY id LIMIT $2`) is not a nice-to-have — for any table that could reach production scale, OFFSET pagination degrades linearly and produces incorrect results during concurrent modifications
- The `DBTX` interface (or sqlc's `Querier`) is the seam that enables testing, middleware injection, read/write splitting, and multi-tenancy — invest in this abstraction early
- PostgreSQL connection limits are not about thread count — each connection is a backend process (forked, not threaded), and >100 active connections cause context-switching overhead that degrades total throughput more than it adds parallelism
