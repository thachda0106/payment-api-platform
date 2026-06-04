# Phase 13 — Observability for Senior Backend Engineers

> **Duration**: 2-3 days | **Prerequisites**: Phases 1-12
>
> **Goal**: Know exactly what to instrument in every backend service by default. Every REST API, gRPC endpoint, Kafka consumer, database query, Redis operation, and background job.

---

## 13.1 The Senior Engineer's Instrumentation Checklist

Every service you build should emit these by default. No exceptions.

### 13.1.1 HTTP/REST API

```yaml
## Every HTTP endpoint must emit:

# 1. RED Metrics (auto-instrumented by OTel)
rate(http_server_duration_seconds_count{http.route="/payments"}[5m])
rate(http_server_duration_seconds_count{http.route="/payments", http.status_code=~"5.."}[5m])
histogram_quantile(0.99, rate(http_server_duration_seconds_bucket{http.route="/payments"}[5m]))

# 2. Request body size (for detecting large payload issues)
http_server_request_size_bytes_count

# 3. Response body size
http_server_response_size_bytes_count

# 4. Active requests (gauge)
http_server_active_requests

## Manual instrumentation adds:

# 5. Business-level spans wrapping the request
Span: "processRefund" with attributes: refund.id, refund.amount, refund.currency

# 6. Business-level counters
payments_processed_total{status="success|error"}

# 7. Business-level error categories (more granular than HTTP status)
payment_errors_total{error_type="INSUFFICIENT_FUNDS|FRAUD_DECLINED|EXTERNAL_TIMEOUT"}
```

**What OTel auto-instrumentation gives you**: Items 1-4. Zero code.
**What you must add manually**: Items 5-7. The business context.

### 13.1.2 gRPC

```yaml
## Every gRPC service must emit:

# 1. RED Metrics (auto-instrumented)
rpc_server_duration_seconds_count{rpc.service="PaymentService", rpc.method="ProcessPayment"}
histogram_quantile(0.99, rate(rpc_server_duration_seconds_bucket{...}[5m]))

# 2. gRPC-specific: message sizes
rpc_server_request_size_bytes
rpc_server_response_size_bytes

# 3. Streaming-specific: messages per stream
rpc_server_stream_messages_received (server streaming)
rpc_server_stream_messages_sent (client streaming)

## Manual instrumentation:

# 4. Error breakdown by gRPC status code
rpc_errors_total{grpc_code="UNAVAILABLE|DEADLINE_EXCEEDED|INTERNAL|..."}
```

### 13.1.3 Kafka

```yaml
## Producer:

# 1. Message send rate + error rate
kafka_producer_messages_total{topic="payments.completed", status="success|error"}

# 2. Message size
kafka_producer_message_size_bytes{topic="payments.completed"}

# 3. Send latency (time to get ack from broker)
kafka_producer_send_duration_seconds{topic="payments.completed"}

# 4. Producer buffer usage (saturation)
kafka_producer_buffer_available_bytes
kafka_producer_waiting_threads  # threads blocked waiting for buffer space

## Consumer:

# 5. Message consume rate
kafka_consumer_messages_total{topic="payments.completed", status="success|error"}

# 6. Consumer lag (THE most important Kafka metric)
kafka_consumer_lag{group="payment-processor", topic="payments.completed", partition="0"}

# 7. Processing duration
kafka_consumer_process_duration_seconds{topic="payments.completed"}

# 8. Rebalance events (count + timestamp)
kafka_consumer_rebalances_total{group="payment-processor"}
```

**Why consumer lag is THE most important metric**: Lag tells you "are we keeping up?" Everything else (throughput, error rate, latency) is secondary. If lag is growing, something is wrong regardless of other metrics. If lag is zero, throughput issues are self-resolving.

### 13.1.4 PostgreSQL

```yaml
## Auto-collected by OTel JDBC instrumentation:

# 1. Query duration per operation
db_client_operation_duration_seconds{
  db.system="postgresql",
  db.operation="SELECT|INSERT|UPDATE|DELETE",
  db.sql.table="payments|wallets|journal_entries"
}

# 2. Query count per operation
db_client_calls_total{db.operation="..."}

## Must add manually:

# 3. Connection pool metrics
db_pool_active_connections          # gauge
db_pool_idle_connections            # gauge
db_pool_pending_connections         # gauge (waiting for connection = saturation)
db_pool_max_connections             # gauge
db_pool_connection_wait_duration    # histogram (how long threads wait for connection)
db_pool_connection_timeouts_total   # counter

# 4. Transaction duration
db_transaction_duration_seconds     # histogram

# 5. Deadlock count
db_deadlocks_total                  # counter
```

**Why connection pool metrics are critical**: Database connection pool exhaustion is the most common database-related outage. Monitoring `pending_connections` and `connection_wait_duration` catches it before it becomes an error spike.

### 13.1.5 Redis

```yaml
## Auto-collected by OTel Redis instrumentation:

# 1. Command duration
db_client_operation_duration_seconds{
  db.system="redis",
  db.operation="GET|SET|INCR|HGET|..."
}

# 2. Cache hit rate (must calculate: hits / total)
redis_commands_total{status="hit|miss"}

## Must add manually:

# 3. Connection pool metrics (same as PostgreSQL)
redis_pool_active_connections
redis_pool_pending_connections

# 4. Redis errors by type
redis_errors_total{error_type="TIMEOUT|CONNECTION_REFUSED|CLUSTER_DOWN"}
```

### 13.1.6 Background Jobs / Async Processing

```yaml
## Every background job must emit:

# 1. Job execution count
background_jobs_total{job="settlement-process", status="completed|failed|retried"}

# 2. Job duration
background_job_duration_seconds{job="settlement-process"}

# 3. Job queue depth (if using a queue)
background_job_queue_depth{queue="settlement"}  # gauge

# 4. Job age in queue (time waiting before processing)
background_job_queue_wait_duration_seconds{queue="settlement"}

# 5. Retry count (per job type)
background_job_retries_total{job="settlement-process"}

# 6. Dead letter queue depth
background_job_dlq_depth{queue="settlement"}
```

**Why job age in queue matters**: A job that waits 10 minutes in the queue before being processed still "succeeds" — but if the SLA is 2 minutes, you're breaching SLO with no errors showing. Job queue wait time catches this.

---

## 13.2 Domain-Specific Instrumentation

### 13.2.1 Payment Systems

```yaml
## Every payment system must emit:

# 1. Payment lifecycle state transitions
payment_state_transitions_total{
  from="CREATED|AUTHORIZED|CAPTURED|SETTLED",
  to="AUTHORIZED|CAPTURED|SETTLED|REFUNDED|FAILED"
}

# 2. Payment processing duration (end-to-end, from API call to confirmation)
payment_processing_duration_seconds{
  payment_type="CARD|BANK_TRANSFER|WALLET"
}

# 3. Payment outcomes
payments_total{outcome="SUCCESS|INSUFFICIENT_FUNDS|FRAUD_DECLINED|BANK_DENIED|TIMEOUT"}

# 4. Amount being processed (real-time gauge for anomaly detection)
payment_amount_in_flight{currency="USD"}  # gauge

# 5. Idempotency key hit rate
idempotency_cache_hits_total
idempotency_cache_misses_total

# 6. Payment method distribution
payments_by_method_total{method="VISA|MASTERCARD|WALLET|BANK_TRANSFER"}

# 7. Fee calculation
fee_calculation_duration_seconds
fee_total{type="INTERCHANGE|MARKUP|SERVICE"}

# 8. Reconciliation (batch processing)
reconciliation_items_total{status="MATCHED|UNMATCHED|DISCREPANCY"}
reconciliation_batch_duration_seconds

# 9. Wallet balance (after every operation)
wallet_balance_after_operation{wallet_type="AVAILABLE|PENDING|HELD"}

# 10. Fraud detection
fraud_check_total{result="PASS|BLOCK|REVIEW"}
fraud_check_duration_seconds
fraud_check_score{score_range="0-0.2|0.2-0.5|0.5-0.8|0.8-1.0"}
```

### 13.2.2 Kafka Event Processing

```yaml
## Every Kafka-driven service must emit:

# 1. Consumer lag (by topic, partition)
kafka_consumer_group_lag

# 2. Processing errors by type
event_processing_errors_total{
  event_type="PaymentCompleted|WalletUpdated|RefundProcessed",
  error_type="DESERIALIZATION|SCHEMA_VALIDATION|BUSINESS_RULE|DB_ERROR"
}

# 3. Event processing latency (time from produce to consume complete)
event_end_to_end_latency_seconds{event_type="PaymentCompleted"}

# 4. Dead letter events
dlq_events_total{event_type="...", reason="..."}

# 5. Schema compatibility failures
schema_registry_compatibility_failures_total
```

### 13.2.3 External API Integration

```yaml
## Every external API call must emit:

# 1. RED metrics for the external call
external_api_requests_total{provider="stripe|paypal|bank-api", endpoint="charge|verify"}
external_api_errors_total{provider="...", error_type="TIMEOUT|CONNECTION_REFUSED|4XX|5XX"}
external_api_duration_seconds{provider="..."}

# 2. Circuit breaker state
circuit_breaker_state{provider="stripe"}  # 0=CLOSED, 1=OPEN, 2=HALF_OPEN

# 3. Rate limit hits
external_api_rate_limits_total{provider="stripe"}

# 4. Retry counts
external_api_retries_total{provider="stripe", attempt="1|2|3"}
```

---

## 13.3 Red Flags in Code Review

As a Senior Engineer, these patterns in PRs should raise immediate red flags:

### 13.3.1 "Fire and Forget" Patterns

```java
// RED FLAG: No trace, no metric, no error handling
CompletableFuture.runAsync(() -> {
    externalService.sendData(data);
});
```

**Why it's dangerous**: If this fails, no trace captures it, no metric counts it, no alert fires. The data is silently lost.

**Fix**:
```java
CompletableFuture.runAsync(() -> {
    Span span = tracer.spanBuilder("send-data-async")
        .setAttribute("data_id", data.getId())
        .startSpan();
    try (Scope s = span.makeCurrent()) {
        externalService.sendData(data);
        sendCounter.add(1, Attributes.of(AttributeKey.stringKey("status"), "success"));
    } catch (Exception e) {
        sendCounter.add(1, Attributes.of(AttributeKey.stringKey("status"), "error"));
        span.recordException(e);
        // Log to DLQ or retry
    } finally {
        span.end();
    }
});
```

### 13.3.2 Hidden Exception Swallowing

```java
// RED FLAG: Silently catches and ignores exceptions
try {
    criticalOperation();
} catch (Exception e) {
    log.debug("Operation failed", e);  // DEBUG level only!
}
```

**Fix**: At minimum, log at ERROR level with context. Better: record in a metric.

```java
try {
    criticalOperation();
} catch (Exception e) {
    log.error("Critical operation failed: id={}", operationId, e);
    errorCounter.add(1, Attributes.of(
        AttributeKey.stringKey("operation"), "critical"
    ));
    throw new OperationFailedException(operationId, e);
}
```

### 13.3.3 Missing Timeouts

```java
// RED FLAG: No timeout on external call
HttpResponse response = httpClient.send(request);
```

**Fix**:
```java
HttpResponse response = httpClient.send(request, 
    HttpResponse.BodyHandlers.ofString(),
    HttpTimeout.ofSeconds(5)  // Always have a timeout
);
```

### 13.3.4 "SELECT *" on Large Tables

```sql
-- RED FLAG: No LIMIT, no indexed WHERE
SELECT * FROM journal_entries ORDER BY created_at DESC;
```

This query works in development (100 rows). It kills production (100M rows, full table scan, connection held for minutes).

**Fix**:
```sql
SELECT * FROM journal_entries 
WHERE created_at BETWEEN '2024-01-15' AND '2024-01-16'
  AND account_id = ?
ORDER BY created_at DESC
LIMIT 100;
```

---

## 13.4 The Service Readiness Checklist

Before a service can go to production, it must have:

```
□ OTel SDK configured (auto-instrumentation + manual for business logic)
□ RED metrics for every endpoint (auto-instrumentation covers this)
□ USE metrics for all resources (connection pools, thread pools, queues)
□ Business-specific counters for critical operations
□ Structured logging (JSON) with trace_id + span_id injection
□ Health check endpoint (/health — liveness) ← for k8s probes
□ Readiness check endpoint (/ready — readiness) ← for k8s probes
□ Alert rules defined in Prometheus (or Grafana)
□ Dashboard in Grafana (RED dashboard minimum)
□ Error budget defined (SLO)
□ Runbook linked from alerts
```

---

## 13.5 Common Misconceptions

### "I can add observability later"

Adding instrumentation AFTER an incident means you have no data to debug the NEXT incident. Instrumentation is like insurance — you pay for it before you need it. Build it into the service from day one.

### "Auto-instrumentation is enough"

Auto-instrumentation gives you HTTP-level RED metrics. It doesn't give you business context: which payment types failed, what fraud score led to a block, which external provider timed out. Manual instrumentation provides the "why."

### "More data = better observability"

More UNSTRUCTURED data = more noise. High-cardinality, high-volume telemetry without filtering buries signal. The goal is the RIGHT data at the RIGHT granularity, not ALL data.

---

## Interview Questions — Phase 13

1. **What metrics should every production service emit at minimum?**

   *Answer core points*: RED for every endpoint (rate, errors, duration via histogram). USE for all resources (connection pools, thread pools, queue depths). Business-level counters for critical operations. Consumer lag for Kafka consumers. Connection wait time for database pools.

2. **You're reviewing a PR that wraps an HTTP call to a payment gateway. What observability concerns do you look for?**

   *Answer core points*: (1) Is there a timeout? (2) Is the call wrapped in a span with provider + endpoint attributes? (3) Is there an error counter incremented on failure? (4) Is there a duration histogram? (5) Is there a circuit breaker? (6) Are retries configured with backoff + jitter? (7) Is the call inside or outside the database transaction? (Should be outside.)

3. **What's the difference between what auto-instrumentation gives you and what manual instrumentation adds?**

   *Answer core points*: Auto-instrumentation: HTTP-level spans (SERVER/CLIENT), JDBC spans, Kafka spans, framework-level metrics. Manual instrumentation: business-level spans (wrap specific operations), business attributes (payment amount, user ID), business counters (payments by status), business events (state transitions). Auto gives you "where." Manual gives you "why."

4. **Why is "job age in queue" important for background job processing?**

   *Answer core points*: A job can succeed (status=completed) but take 10 minutes in the queue. If the SLA is 2 minutes, you're breaching SLO with zero errors showing. Job age in queue measures the FULL end-to-end time from enqueue to processing start, catching queue backlog before it becomes latency in consumer metrics.

---

**Next: Phase 14 — Staff Engineer Level**
