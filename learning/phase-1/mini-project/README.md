# Mini Project — HTTP Load Balancer

## Goal

Build an L7 HTTP load balancer that distributes incoming requests across a pool of backend servers, handles failures gracefully, and supports both round-robin and least-connections algorithms.

## Time Estimate
10-12 hours

## Requirements

### Core Features

1. **Request Forwarding**
   - Accept HTTP connections on a configurable port
   - Forward each request to a selected backend server
   - Return the backend's response to the client
   - Support multiple concurrent connections (thread pool or virtual threads)

2. **Load Balancing Algorithms**
   - **Round-robin**: Cycle through backends sequentially
   - **Least-connections**: Route to backend with fewest active connections
   - Switchable at runtime via API or config

3. **Health Checking**
   - Every 5 seconds, check each backend by sending `GET /health`
   - A backend is HEALTHY if it responds within 2 seconds
   - A backend is UNHEALTHY after 3 consecutive failures
   - Automatically remove unhealthy backends, add back when healthy

4. **Failure Handling**
   - If the selected backend is unreachable, try the next backend (max 2 retries)
   - If all backends are unhealthy, return HTTP 503
   - Log failed attempts with backend address and reason

5. **Observability**
   - Log every request: client IP → backend address, response time, status
   - Track metrics: requests/second, error rate, average response time
   - Expose `GET /__admin/health` and `GET /__admin/backends` endpoints

## Architecture

```
                    ┌──────────────────┐
                    │  LOAD BALANCER   │
                    │  :8080           │
                    └────┬──┬──┬───────┘
                         │  │  │
              ┌──────────┘  │  └──────────┐
              ▼             ▼             ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ Backend 1│ │ Backend 2│ │ Backend 3│
        │  :9091   │ │  :9092   │ │  :9093   │
        └──────────┘ └──────────┘ └──────────┘
```

## Stretch Goals

1. **Sticky sessions**: Route same client (by IP or cookie) to same backend
2. **TLS termination**: Accept HTTPS, forward HTTP to backends
3. **Rate limiting**: Per-client rate limiting (token bucket)
4. **Configuration hot-reload**: Reload backend list without restarting
5. **Admin UI**: Simple web UI showing backend status and metrics

## Test Plan

1. Start 3 backend servers on different ports
2. Start load balancer on port 8080
3. Send 100 requests via curl — verify roughly equal distribution
4. Kill one backend — verify requests are rerouted to remaining backends
5. Restart killed backend — verify health check detects it and adds back to pool
6. Kill all backends — verify 503 response
7. Switch algorithm to least-connections — verify behavior changes

## Connection to Phase 2

This project directly relates to:
- **Connection pooling** in PostgreSQL (HikariCP) — same principle: pool of connections, round-robin/least-busy selection
- **Kafka consumer groups** — partition assignment is load balancing
- **Kubernetes Services** — kube-proxy is an L4 load balancer for pods
- **API Gateway** (Kong) — L7 load balancing with health checks, TLS termination
