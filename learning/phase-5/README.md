# Phase 5 — Go Deep Dive

> **Duration**: 2-3 weeks (full-time) | **Prerequisites**: Phase 2 (DB Fundamentals)
>
> **Goal**: Build high-concurrency Go services, understand goroutine scheduling (GMP), write production Go that doesn't leak goroutines or memory, and use pprof + race detector for debugging.
>
> **Why Go for the payment platform**: Settlement, Reconciliation, Compliance, and Bank Integration services use Go for: low resource footprint (2-5MB per service), goroutine concurrency (millions of goroutines for parallel batch processing), single-binary deployment (scratch Docker images), and fast cold start (milliseconds vs JVM seconds).

## Study Plan

| Day | Module | Topics | Hours |
|-----|--------|--------|:-----:|
| 1-3 | Module 01 | Syntax, slices/arrays, maps, structs, interfaces, error handling, generics | 10h |
| 4-6 | Module 02 | Goroutines, channels (buffered/unbuffered), select, patterns (fan-in/fan-out, pipeline) | 10h |
| 7-8 | Module 02 | sync package (Mutex, RWMutex, WaitGroup, Once, Pool), context, atomic | 8h |
| 9-11 | Module 03 | GMP scheduler, escape analysis, GC, pprof, race detector, trace | 10h |
| 12-13 | Module 03 | Standard library, testing (table-driven, benchmarks, fuzz), production patterns | 8h |
| 14-18 | Mini Project | Concurrent Settlement Engine | 15h |

## Setup

```bash
go version  # Should be 1.22+
go install golang.org/x/tools/cmd/godoc@latest
go install github.com/go-delve/delve/cmd/dlv@latest
go install github.com/golangci/golangci-lint/cmd/golangci-lint@latest
```

## Resources

- **Book**: "The Go Programming Language" (Donovan & Kernighan)
- **Book**: "Concurrency in Go" (Cox-Buday)
- **Blog**: "Go Scheduler" series, "Go Memory Management" (dave.cheney.net)
- **Doc**: Effective Go, Go Memory Model, Go Blog
