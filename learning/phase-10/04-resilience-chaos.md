# Module 04 — Resilience, Backpressure, Locks & Chaos

## 4.1 Circuit Breaker

Prevents cascading failures. Three states:

**Closed** → Normal. Requests go through. Count failures.
**Open** → Failures exceed threshold (e.g., 50% in 10s). Requests immediately fail (fast-fail). No calls to downstream.
**Half-Open** → After timeout (e.g., 30s). Allow ONE probe request. If succeeds → Closed. If fails → Open.

```java
// Resilience4j example (Spring Boot)
@CircuitBreaker(name = "fraudService", fallbackMethod = "fraudFallback")
public FraudResult checkFraud(Payment payment) {
    return fraudService.check(payment);  // Remote call
}
public FraudResult fraudFallback(Payment p, Exception e) {
    return FraudResult.ALLOW;  // Fail-open: allow payment if fraud check unavailable
}
```

## 4.2 Bulkhead

Isolate resources per downstream. Separate thread pools for Fraud vs Ledger calls. If Fraud is slow, it exhausts its own pool, not the Ledger pool.

```java
@Bulkhead(name = "fraudService", type = Bulkhead.Type.THREADPOOL)
public FraudResult checkFraud(Payment p) { ... }
// maxThreadPoolSize=10, maxWaitDuration=50ms
```

## 4.3 Backpressure

Don't accept work you can't process.

**TCP flow control**: Receiver advertises window size → sender throttles.

**Reactive Streams**: Consumer requests N items (`request(n)`), producer sends at most N.

**Load Shedding**: When queue depth exceeds threshold, reject new requests immediately (HTTP 503). Better to fail fast than queue and timeout.

**Rate Limiting (Token Bucket)**: N tokens refilled at rate R. Each request consumes 1 token. No tokens → rate limited (429).

```lua
-- Redis token bucket (atomic Lua script)
local tokens = redis.call('HGET', KEYS[1], 'tokens')
if tokens and tonumber(tokens) > 0 then
    redis.call('HINCRBY', KEYS[1], 'tokens', -1)
    return 1  -- allowed
end
return 0  -- rate limited
```

## 4.4 Distributed Locks

### Redis Redlock

5 independent Redis instances. Acquire lock from majority (3/5) with TTL. Clock drift between instances is the vulnerability — a lock that expires on one node may still appear valid on another.

### PostgreSQL Advisory Locks

```sql
SELECT pg_advisory_xact_lock(12345);  -- Lock released at transaction end
```
Simpler, no clock drift issue. BUT: single PostgreSQL instance (single point of failure).

### Fencing Token (Critical!)

Every lock acquisition returns a monotonically increasing **fencing token** (e.g., current transaction ID). The resource (e.g., database) checks that the token is HIGHER than the last seen token before accepting writes. Prevents stale lock access after network partition.

## 4.5 Chaos Engineering

### Method

1. **Define steady state**: What does normal look like? (Payment success rate > 99.95%, P99 < 500ms)
2. **Hypothesize**: "If we kill Fraud Service pod, Payment falls back to cached scores and continues."
3. **Inject failure**: Kill the pod. Add 500ms latency. Exhaust disk space.
4. **Observe**: Does the system behave as hypothesized? Monitor steady state metrics.
5. **Learn & fix**: If hypothesis was wrong, fix system BEFORE this failure happens in production.

### Payment Platform Chaos Experiments

| Experiment | Hypothesis | Observe |
|-----------|-----------|---------|
| Kill Fraud Service pod | Payment falls back to cached scores | Success rate, P99 |
| Kill 1 of 3 Kafka brokers | Producers retry, no data loss | Producer error rate, consumer lag |
| +500ms latency to Ledger | Circuit breaker opens after threshold | Circuit breaker state |
| Kill entire region (simulated) | DR region takes over | Failover time, data consistency |
| DNS failure (CoreDNS) | Services use cached IPs | Inter-service call success |

## 4.6 Exercises

### Ex 4.1 — Circuit Breaker from Scratch
Implement a circuit breaker: Closed → Open (50% failures, 10s window) → Half-Open (30s timeout, 1 probe) → Closed/Open. Test with a flaky downstream.

### Ex 4.2 — Rate Limiter
Implement a token bucket rate limiter using Redis Lua script. Test: 100 concurrent clients, each trying 100 requests, rate limited to 10/s. Verify no more than 10 requests/second are allowed.

### Ex 4.3 — Chaos Experiment Design
Design 5 chaos experiments for the payment platform. For each: define steady state, hypothesis, method, metrics to observe. Start in staging, graduate to production.

---

## 4.7 Self-Assessment

- [ ] Can implement a circuit breaker with all three states
- [ ] Understand the difference between bulkhead (isolation) and circuit breaker (failure detection)
- [ ] Can explain why fencing tokens are necessary for distributed locks
- [ ] Know the difference between load shedding and rate limiting
- [ ] Can design a chaos experiment using the scientific method
