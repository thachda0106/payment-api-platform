# Mini Project — Thread-Safe Idempotency Store

## Goal

Build an in-memory, thread-safe idempotency key store with TTL-based expiration and LRU eviction — the same pattern used by Stripe, PayPal, and our payment platform.

## What You Will Build

A concurrent data structure that supports:
- **O(1) set**: `setIfAbsent(key, response)` — atomically stores if key doesn't exist, returns false if duplicate
- **O(1) get**: `get(key)` — retrieves stored response if key exists and not expired, returns empty if not found
- **TTL expiration**: Keys expire automatically after configurable TTL (background cleaner thread)
- **LRU eviction**: When capacity is reached, evict the least recently used entry
- **Thread safety**: Correct under concurrent access from multiple threads
- **Metrics**: Hit rate, miss rate, eviction count, current size

## Architecture

```
┌──────────────────────────────────────────────────────┐
│              IdempotencyStore                         │
│                                                       │
│  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │ ConcurrentHashMap │  │  Doubly-Linked List (LRU)│  │
│  │   key → Entry     │  │  head(MRU) ↔ ... ↔ tail  │  │
│  └────────┬─────────┘  └───────────┬──────────────┘  │
│           │                         │                  │
│  ┌────────▼─────────────────────────▼──────────────┐  │
│  │                   Entry                           │  │
│  │  key | response | expiresAt | prev | next        │  │
│  └──────────────────────────────────────────────────┘  │
│                                                       │
│  ┌──────────────────────────────────────────────────┐  │
│  │  Background Cleaner (ScheduledExecutorService)    │  │
│  │  Runs every 1s: removes expired entries           │  │
│  └──────────────────────────────────────────────────┘  │
│                                                       │
│  ┌──────────────────────────────────────────────────┐  │
│  │  Metrics: hits, misses, evictions, expirations    │  │
│  └──────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

## Files

- `IdempotencyStore.java` — Complete implementation with tests

## Run

```bash
javac IdempotencyStore.java && java IdempotencyStore
```

## Acceptance Criteria

1. `setIfAbsent` returns true for new key, false for duplicate
2. Expired keys return empty on `get` and can be re-inserted
3. LRU eviction removes least recently used entry when capacity exceeded
4. Concurrent access: 100 threads × 1000 ops each → all operations consistent, no data loss
5. Metrics counters are accurate under concurrent access

## What You Will Learn

- How `ConcurrentHashMap.putIfAbsent` enables lock-free idempotency checks
- How to combine `ConcurrentHashMap` (fast O(1) lookups) with a doubly-linked list (LRU ordering)
- How to handle TTL expiration with a background cleaner thread
- How to coordinate access between a concurrent map and a linked list using `ReentrantLock`
- How to implement metrics with `AtomicLong` for lock-free counters
