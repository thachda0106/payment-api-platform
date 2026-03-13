---
description: Identify and fix performance bottlenecks with evidence-based analysis
agent: performance-optimizer
---

# Optimize Performance

Identify and fix performance bottlenecks using evidence-based analysis.

## Steps

### 1. Identify the Problem

- What is slow? (specific endpoint, operation, page load)
- How slow? (current vs. expected latency/throughput)
- Since when? (regression or longstanding issue)

### 2. Profile and Measure

- Trace execution flow for the slow path
- Identify the bottleneck stage (DB query, computation, I/O, network)
- Gather evidence (query logs, execution time, memory usage)

### 3. Diagnose Root Cause

Common performance issues:
- **Database**: N+1 queries, missing indexes, full table scans, unoptimized joins
- **Computation**: O(n²) algorithms, unnecessary iterations, blocking operations
- **I/O**: Synchronous file operations, unbatched network calls
- **Memory**: Memory leaks, excessive object creation, missing cleanup
- **Concurrency**: Blocking main thread, incorrect async patterns

### 4. Apply Optimization

- Fix the specific bottleneck identified
- Prefer simple solutions (add index, batch queries) over complex ones
- Do NOT optimize areas without evidence of problems

// turbo
### 5. Verify Improvement

- Measure the optimized path
- Compare before/after metrics
- Run regression tests

## Critical Rules

- **No evidence = no optimization**
- **Measure first, optimize second**
- **One bottleneck at a time**
- **Verify improvement with data**
