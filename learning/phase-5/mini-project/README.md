# Mini Project — Concurrent Settlement Engine

## Goal

Build a concurrent batch settlement engine that reads payment transactions, processes them in parallel using a worker pool, aggregates results per merchant, and generates settlement reports — with graceful shutdown, pprof monitoring, and metrics.

## What You Will Build

A Go service that:
- Reads payments from a CSV file (simulating database output)
- Processes payments concurrently using a configurable worker pool
- Calculates per-merchant settlement: total amount, transaction count, fees, net amount
- Supports graceful shutdown via context cancellation
- Exposes pprof endpoints for monitoring goroutines, heap, and CPU
- Reports throughput metrics

## Architecture

```
CSV File (payments.csv)
       │
       ▼
  Payment Reader → []Payment
       │
       ▼
  Settlement Engine
       │
  ┌────┼────┬────┬────┐ (Worker Pool: N goroutines)
  │    │    │    │    │
  ▼    ▼    ▼    ▼    ▼
  Results Channel → Aggregate by Merchant → Settlement Report
```

## Run

```bash
go run settlement_engine.go
# Then: go tool pprof http://localhost:6060/debug/pprof/profile?seconds=10
```

## Acceptance Criteria

1. Processes 10,000 payments in < 5 seconds with 8 workers
2. Correctly aggregates amounts per merchant (no race conditions)
3. Graceful shutdown via SIGTERM (workers finish in-flight jobs, then exit)
4. pprof accessible at `http://localhost:6060/debug/pprof/`
5. Metrics: processed count, total amount, errors

## What You Will Learn

- Worker pool pattern with goroutines and channels
- Aggregating concurrent results with sync.WaitGroup + channel close
- Context propagation for cancellation
- atomic.Int64 for lock-free metrics
- pprof integration for production monitoring
