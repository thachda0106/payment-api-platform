# Phase 14 — Staff Engineer Level

> **Duration**: 1 week | **Prerequisites**: ALL previous phases
>
> **Goal**: Design observability platforms for 10, 100, and 1000 services. Make architecture decisions with justified trade-off reasoning. Handle cost, scaling, retention, multi-tenancy, security, and disaster recovery.

---

## 14.1 The Platform Evolution

### 14.1.1 Stage 1: 10 Services (Startup)

```
Characteristics:
  - 1-2 teams
  - Single EKS cluster
  - < 100 GB logs/day
  - < 10,000 spans/sec
  - < 1M active Prometheus series
  - Budget: < $1,000/month

Architecture:
  ┌──────────────────────────────────────────┐
  │              observability ns             │
  │                                           │
  │  OTel Collector (DaemonSet)              │
  │       ↓                                   │
  │  ┌────────┐  ┌────────┐  ┌────────────┐ │
  │  │Prom    │  │ Jaeger │  │OpenSearch  │ │
  │  │(single)│  │(all-in)│  │(single)    │ │
  │  └────────┘  └────────┘  └────────────┘ │
  │       ↓            ↓            ↓         │
  │  ┌──────────────────────────────────┐   │
  │  │         Grafana (single)         │   │
  │  └──────────────────────────────────┘   │
  └──────────────────────────────────────────┘

Design Decisions:
  - No gateways — DaemonSet collectors write directly to backends
  - Single Prometheus (vertical scaling is fine)
  - Jaeger all-in-one (badger storage, 7 day retention)
  - OpenSearch single-node (snapshot backups, no HA needed)
  - No tail sampling (head sampling 10% is sufficient)

Trade-offs:
  ✓ Simple, low ops burden
  ✓ Low cost
  ✗ No HA — single Prometheus failure = blind
  ✗ No tail sampling — will miss interesting anomalies
  ✗ Doesn't scale beyond 20 services

When to move to Stage 2:
  - Prometheus series count > 2M
  - OpenSearch node maxed out (CPU/memory)
  - Need HA (multiple Availability Zones)
  - Adding second cluster/region
```

### 14.1.2 Stage 2: 100 Services (Growth)

```
Characteristics:
  - 5-10 teams
  - 2-3 EKS clusters (or multi-AZ)
  - 100-500 GB logs/day
  - 10,000-50,000 spans/sec
  - 2-5M active Prometheus series
  - Budget: $3,000-$8,000/month

Architecture:
  ┌──────────────────────────────────────────────────────────┐
  │              observability ns (per cluster)               │
  │                                                           │
  │  ┌─────────────────┐                                     │
  │  │ OTel DaemonSet  │──→ OTel Gateway (3 replicas, HA)   │
  │  └─────────────────┘    │ Tail sampling                 │
  │                         │ K8s attribute enrichment       │
  │                         ↓                                 │
  │  ┌──────────┐  ┌──────────────┐  ┌──────────────────┐  │
  │  │Prometheus│  │Jaeger (prod) │  │OpenSearch (3-node│  │
  │  │(2 reps)  │  │Collector(3)  │  │hot + 3-node warm)│  │
  │  │+ Thanos  │  │Query(2)      │  │+ ILM             │  │
  │  │  Sidecar │  │+ ES backend  │  │                  │  │
  │  └────┬─────┘  └──────────────┘  └──────────────────┘  │
  │       │ S3                                                │
  │       ↓                                                   │
  │  ┌──────────────┐                                         │
  │  │ Alertmanager │ (2 replicas, HA)                        │
  │  └──────────────┘                                         │
  │       ↓                                                   │
  │  ┌──────────────┐                                         │
  │  │ Grafana (2)  │                                         │
  │  └──────────────┘                                         │
  └──────────────────────────────────────────────────────────┘

Design Decisions:
  - Gateway collectors for centralized tail sampling + backend auth
  - Prometheus HA (2 replicas) + Thanos for long-term retention
  - Jaeger production mode (separate collector + query + ES storage)
  - OpenSearch dedicated master + data (hot) + data (warm) nodes
  - Alertmanager HA (2 replicas in mesh mode)
  - S3 for Thanos block storage and OpenSearch snapshots

Trade-offs:
  ✓ HA across components
  ✓ Tail sampling catches all errors/slow traces
  ✓ Thanos provides unlimited retention + global query
  ✓ ILM manages storage costs
  ✗ Higher ops complexity (multiple components)
  ✗ Higher baseline cost (redundancy × 2-3)
  ✗ Need to manage PVC sizing and expansion

When to move to Stage 3:
  - 5+ EKS clusters
  - Prometheus series count > 10M per cluster
  - Need centralized global query view
  - Multi-tenancy requirements (separate teams need isolated views)
  - SLO-based budgeting across organization
```

### 14.1.3 Stage 3: 1000 Services (Enterprise)

```
Characteristics:
  - 20+ teams, multiple business units
  - 10+ EKS clusters across regions
  - 2-5 TB logs/day
  - 100,000-500,000 spans/sec
  - 10-50M active Prometheus series
  - Budget: $15,000-$50,000+/month

Architecture:
                              ┌───────────────────────────┐
                              │   Central Observability    │
                              │   (aggregation + query)    │
                              │                            │
                              │   Thanos Query (global)    │
                              │   Jaeger Query (global)    │
                              │   OpenSearch (cross-cluster│
                              │     search)                │
                              │   Grafana (multi-tenant)   │
                              │   Central Alertmanager     │
                              └──────┬─────────────────────┘
                                     │
        ┌────────────────────────────┼────────────────────────────┐
        │                            │                            │
┌───────▼──────┐            ┌───────▼──────┐            ┌───────▼──────┐
│  EKS us-east │            │  EKS eu-west │            │  EKS ap-se-1  │
│              │            │              │            │              │
│ Prom HA × 2  │            │ Prom HA × 2  │            │ Prom HA × 2  │
│ Thanos Side  │            │ Thanos Side  │            │ Thanos Side  │
│ OTel GW × 5  │            │ OTel GW × 5  │            │ OTel GW × 5  │
│ Jaeger Col×3 │            │ Jaeger Col×3 │            │ Jaeger Col×3 │
│              │            │              │            │              │
│ AWS Services:│            │ AWS Services:│            │ AWS Services:│
│ AMP (remote  │            │ AMP (remote  │            │ AMP (remote  │
│   write)     │            │   write)     │            │   write)     │
│ OpenSearch S.│            │ OpenSearch S.│            │ OpenSearch S.│
└──────┬───────┘            └──────┬───────┘            └──────┬───────┘
       │                           │                           │
       └───────────────────────────┼───────────────────────────┘
                                   │
                          ┌────────▼───────┐
                          │   AWS S3       │
                          │  (Thanos blocks│
                          │   from all DCs)│
                          └────────────────┘

Design Decisions:
  - Per-cluster Prometheus + Thanos Sidecar → S3
  - Thanos Query (central) provides global PromQL across all clusters
  - AMP remote write for long-term managed retention (compliance)
  - AWS OpenSearch Service per region (reduces cross-region data transfer)
  - Central Grafana with team-level folders and permissions
  - OAuth2/OIDC authentication for all observability UIs
  - SLO-based alerting with error budgets per team

Global Query Architecture:
  ┌────────────────────────────────────────────────────────┐
  │                    Thanos Querier                       │
  │  Query: rate(http_requests_total{region=~".+"}[5m])    │
  └────┬──────────────┬──────────────┬─────────────────────┘
       │              │              │
  ┌────▼──────┐ ┌────▼──────┐ ┌────▼──────┐
  │ Thanos    │ │ Thanos    │ │ Thanos    │
  │ Store     │ │ Store     │ │ Store     │
  │ (us-east) │ │ (eu-west) │ │ (ap-se-1) │
  └───────────┘ └───────────┘ └───────────┘
       │              │              │
  ┌────▼──────┐ ┌────▼──────┐ ┌────▼──────┐
  │ S3 bucket │ │ S3 bucket │ │ S3 bucket │
  │ us-east   │ │ eu-west   │ │ ap-se-1   │
  └───────────┘ └───────────┘ └───────────┘
```

---

## 14.2 Cost Modeling

### 14.2.1 Cost Drivers

```
Total Cost = Compute + Storage + Network + Managed Services + Human Ops

Compute:   EC2/EKS nodes running observability components
Storage:   EBS volumes + S3 (Thanos, backups)
Network:   Cross-AZ data transfer + cross-region replication + NAT Gateway
Managed:   AMP, AWS OpenSearch Service, Grafana Cloud
Human Ops: Team time spent managing the platform
```

### 14.2.2 Cost Optimization Strategies

**1. Sampling aggressively on the write path:**

```
Without sampling:
  100% spans → 100% storage cost → 100% query cost

With head sampling (10%):
  10% spans → 10% storage cost → 10% query cost
  BUT: loses 90% of error traces

With tail sampling:
  100% → Collector buffer → keep 100% of errors, 10% of success
  Storage: ~15% of original
  ✓ Keeps ALL important traces
  ✗ Higher collector resource cost
```

**2. ILM aggressively on logs:**

```
Hot (NVMe, 3 days):  3% of retention window, 40% of storage cost
Warm (HDD, 30 days): 20% of retention window, 30% of storage cost
Cold (S3, 365 days): 77% of retention window, 25% of storage cost
Delete:              Oldest 5%, zero cost

Cost reduction from ILM: ~60% vs all hot storage
```

**3. Thanos for metric long-term retention:**

```
Prometheus local: 30 days, EBS gp3 ($0.08/GB-month)
  → 1 TB × $0.08 = $80/month

Thanos S3: unlimited retention, S3 Standard ($0.023/GB-month)
  → 10 TB × $0.023 = $230/month

Cost difference: $80/TB-month (EBS) vs $23/TB-month (S3)
  → 71% cheaper for long-term data
```

**4. Reduce cross-AZ data transfer:**

```
OTel Collector (AZ-a) → Prometheus (AZ-a):  free
OTel Collector (AZ-a) → Prometheus (AZ-b):  $0.01/GB

With 100 services sending metrics:
  50 GB/day × 30 days × $0.01 = $15/month (manageable)

With 1000 services:
  500 GB/day × 30 days × $0.01 = $150/month

Mitigation:
  - DaemonSet Collectors send to local AZ Gateway
  - Gateway sends to local AZ backend
  - Only Thanos Query crosses AZs (reads, not writes)
  - Minimal cross-AZ = minimal data transfer cost
```

---

## 14.3 Multi-Tenancy

### 14.3.1 Tenant Isolation Models

**Soft Multi-Tenancy** (same cluster, logical separation):

```
Prometheus:
  ┌────────────────────────────────────┐
  │  tenant="payments"    (300K series) │
  │  tenant="auth"        (100K series) │
  │  tenant="ledger"      (200K series) │
  │  tenant="fraud"       (150K series) │
  │  Total: 750K series                 │
  └────────────────────────────────────┘

Grafana:
  - Organization "Payments Team" → sees tenant=payments data
  - Organization "Auth Team"     → sees tenant=auth data
  - Organization "Platform"      → sees all tenants
```

**Hard Multi-Tenancy** (separate instances per tenant):

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ Prometheus   │  │ Prometheus   │  │ Prometheus   │
│ (payments)   │  │ (auth)       │  │ (ledger)     │
│ 2 replicas   │  │ 1 replica    │  │ 2 replicas   │
│ 8 GB RAM     │  │ 4 GB RAM     │  │ 4 GB RAM     │
└──────────────┘  └──────────────┘  └──────────────┘
```

**When to choose hard multi-tenancy:**
- Conflict of interest (separate business units)
- Very different scale (authentication = 100 req/s vs payments = 10,000 req/s)
- Compliance requirements (PCI-DSS data isolation)
- Noisy neighbor prevention (one tenant's cardinality explosion doesn't affect others)

**Cost trade-off:**
- Soft: 1 Prometheus (8 GB) = 1× cost
- Hard: 3 Promethei (8 + 4 + 4 GB) = 2× cost
- Hard pays for isolation and blast radius reduction

### 14.3.2 Tenant Metrics

Every tenant (team/service-group) should have:

```
Tenant Dashboard:
  ┌────────────────────────────────────────────────┐
  │ Team: Payments           Error Budget: 72.3%   │
  │                                                    │
  │ SLO Compliance (30-day rolling)                     │
  │ ████████████████████████████████████░░  98.7%       │
  │                                ↑ SLO: 99.9%        │
  │                                                    │
  │ Error Budget Burn Rate    [------] 0.3x  (safe)    │
  │ P99 Latency               [------] 245ms (normal)   │
  │ Total Spans/Sec           [------] 8,500   (normal) │
  │ Log Volume/Day            [------] 12 GB   (normal) │
  │                                                    │
  │ Monthly Cost: $342  (AMP: $45 | OS: $212 | S3: $85)│
  └────────────────────────────────────────────────┘
```

**Chargeback model**: Each team sees their observability cost. Teams that emit excessive logs or high-cardinality metrics see higher costs. This incentivizes efficient instrumentation.

---

## 14.4 Security

### 14.4.1 Authentication and Authorization

```
Observability Stack Security Layers:

1. Network: VPC private subnets, security groups, no public endpoints
2. Transport: TLS everywhere (OTLP gRPC, Prometheus scrape, OpenSearch)
3. Authentication: OAuth2/OIDC (Okta, Auth0, AWS Cognito)
4. Authorization: Grafana RBAC (Viewer, Editor, Admin per org/folder)
5. API: API keys with scoped permissions (read-only for dashboards)
6. Data: PII filtering in OTel Collector (hash/delete sensitive attributes)
```

### 14.4.2 PII and Sensitive Data

```
OTel Collector Attribute Processor — PII Sanitization:

processors:
  attributes:
    actions:
      # Delete known PII fields
      - key: user.email
        action: delete
      - key: user.phone
        action: delete
      - key: credit_card.number
        action: delete

      # Hash semi-sensitive fields (preserve cardinality for debugging)
      - key: user.id
        action: hash
      - key: account.id
        action: hash

      # Truncate long fields
      - key: http.request_body
        action: delete  # Never log request bodies in production

      # Redact specific values
      - key: exception.stacktrace
        action: delete  # Or hash based on retention policy
```

**Golden rule of observability PII**: If you wouldn't log it, don't trace it. Telemetry is data. Data has compliance obligations. GDPR, CCPA, PCI-DSS apply to traces and logs just like databases.

---

## 14.5 Disaster Recovery

### 14.5.1 Recovery Objectives

| Component | RPO (Recovery Point) | RTO (Recovery Time) | Strategy |
|-----------|---------------------|-------------------|----------|
| Prometheus | 0 (HA replicas) | < 1 minute | 2+ replicas, remote write to Thanos/AMP |
| Jaeger | Minutes (ES replication lag) | < 10 minutes | Multi-node ES cluster, snapshot to S3 |
| OpenSearch | Minutes (replication lag) | < 30 minutes | Multi-node cluster, daily snapshots to S3 |
| Grafana | 0 (no state) | < 5 minutes | Deploy from IaC, dashboards in Git (provisioning) |
| Alertmanager | 0 (mesh) | < 1 minute | 3+ replicas in mesh mode (gossip protocol) |
| OTel Collector | 0 (stateless, with file storage) | Instantaneous | DaemonSet + Deployment, self-healing via k8s |

### 14.5.2 What to Back Up

```
Critical Backups:
  ✓ Prometheus TSDB: No backup needed (Thanos S3 = backup)
  ✓ OpenSearch: Daily snapshots to S3 (automated via ISM policy)
  ✓ Grafana dashboards: Store in Git (provisioning), not in Grafana DB
  ✓ Alertmanager config: In Git (IaC)
  ✓ OTel Collector config: In Git (IaC)

Not Critical (redundant):
  ✗ Prometheus WAL: Replayed from replicas, not backed up
  ✗ Jaeger traces: Can re-sample from Kafka replay (if Kafka pipeline exists)
  ✗ Node Exporter data: Regenerated continuously
```

### 14.5.3 Regional Disaster Recovery

```
Primary Region (us-east-1):  All production traffic
DR Region (us-west-2):       Standby observability stack

DR Readiness:
  - Observability IaC deployed in DR region (Terraform/Crossplane)
  - AMP workspace in DR region (no data until failover)
  - OpenSearch in DR region (no data until replication enabled)
  - Thanos Querier can read from S3 in DR region (S3 is cross-region replicated)

Failover Procedure:
  1. DNS: grafana.example.com → DR Grafana ALB
  2. Application traffic shifts to DR (EKS or failover)
  3. DR OTel Collectors begin receiving telemetry
  4. DR Prometheus + AMP begin storing metrics
  5. DR OpenSearch receives logs
  6. Thanos Querier → S3 in DR region (historical data still queryable)
  7. Alertmanager in DR region begins alerting

RTO: < 30 minutes (automated via IaC)
RPO: < 5 minutes (current data lost, historical data in S3 preserved)
```

---

## 14.6 Organizational Patterns

### 14.6.1 The Observability Team

| Role | Responsibility |
|------|---------------|
| **Platform Engineer** | Deploy and maintain the observability stack (K8s + AWS) |
| **SRE / On-Call** | Respond to alerts, write postmortems, tune alerting |
| **Staff Engineer** | Design architecture, set instrumentation standards, review PRs |
| **Engineering Manager** | Own team SLOs, error budgets, observability maturity |

### 14.6.2 Shared Responsibility Model

```
Observability Platform Team provides:
  ✓ OTel Collector infrastructure (DaemonSet + Gateway)
  ✓ Prometheus / Jaeger / OpenSearch / Grafana instances
  ✓ Auto-instrumentation tooling (Java agent, Node packages)
  ✓ Instrumentation libraries (wrappers for common patterns)
  ✓ Dashboard templates (copy-paste for new services)
  ✓ Alert rule templates
  ✓ Documentation and training

Service Teams are responsible for:
  ✓ Adding manual instrumentation (business context)
  ✓ Defining their SLOs
  ✓ Maintaining their dashboards (from template)
  ✓ Responding to their alerts
  ✓ Reviewing their telemetry in PRs
  ✓ Managing their observability cost
```

---

## 14.7 Architecture Decision Records (ADR)

### 14.7.1 ADR-001: Why We Chose Prometheus + Thanos Over a Commercial APM

```
Decision: Use Prometheus (self-hosted) + Thanos for metrics storage and query.

Alternatives considered:
  1. Datadog APM: $15/host/month. 100 hosts = $1,500/month.
     → Rejected: Vendor lock-in. Per-host pricing doesn't scale.
     Custom metrics extra. Data cannot be exported.
  
  2. Grafana Cloud (Mimir): $0.03/10K series. At 5M series = $15/month.
     → Considered: Good pricing. But data egress limits and query throttling.
     Used as secondary remote_write target for long-term retention.
  
  3. Self-hosted Prometheus + Thanos: EC2 cost ~$400/month for 2× r5.xlarge.
     → Chosen: Full control. No data egress costs. Unlimited query capacity.
     Unlimited retention via S3. Open ecosystem integration.

Trade-off: Higher ops burden for self-hosted.
Justification: At 5M+ series, self-hosted cost savings exceed ops cost.
Review: Re-evaluate at 50M+ series or when team size decreases.
```

### 14.7.2 ADR-002: Why We Use Tail Sampling Despite Higher Collector Cost

```
Decision: Use tail sampling in OTel Gateway Collectors.

Alternatives considered:
  1. Head sampling only (10%): Simple. Low cost. Loses 90% of error traces.
     → Rejected: Cannot debug rare failures. Missing critical observability.
  
  2. Tail sampling (keep errors + slow, sample rest): Collector cost +30%.
     → Chosen: 100% error traces retained. 100% slow traces retained.
     Only 10% of successful, fast traces stored. Total storage: ~15% of unsampled.

  3. 100% sampling: Simple. Expensive. 10× more storage + query cost.
     → Rejected: Cost-prohibitive at 50,000 spans/sec.

Trade-off: Higher collector compute cost (+30%) for dramatically better trace quality.
Justification: Error/slow traces are where debugging value lives. Success+fast traces are mostly noise.
Review: Re-evaluate when span volume exceeds 200,000/sec.
```

---

## 14.8 Common Misconceptions at Staff Level

### "The best observability platform is whatever the vendor sells"

Staff engineers DESIGN platforms, not buy them. Understanding internals allows you to:
- Evaluate vendors honestly (does their architecture scale for your use case?)
- Negotiate pricing (you know their costs)
- Build fallback plans (if the vendor raises prices or has an outage)
- Extend the platform (vendor APIs are not final)

### "Cost optimization is a finance problem"

Observability costs grow linearly with traffic. At 1000 services, observability can cost MORE than the application infrastructure. Staff engineers must model costs at design time and provide chargeback mechanisms.

### "Standardize on one backend for everything"

No single backend handles all observability workloads optimally:
- Metrics → time-series database (Prometheus) — not OpenSearch
- Traces → trace-optimized storage (Jaeger/Elasticsearch) — not Prometheus
- Logs → search engine (OpenSearch) — not Prometheus

The stack is composable by design. Trying to use one tool for everything produces mediocre results.

---

## Interview Questions — Phase 14

1. **Design an observability platform for a company with 500 microservices across 3 regions. Include scaling, cost, and multi-tenancy considerations.**

   *Answer core points*: Per-region: Prometheus HA + Thanos Sidecar → S3. OTel DaemonSet → Gateway (tail sampling) → Jaeger + OpenSearch. Central: Thanos Querier for global PromQL, Jaeger Query, Grafana. Multi-tenancy via Grafana orgs + tenant label. Cost: ~$20K/month. Chargeback per team based on series count + log volume. SLO-based alerting with error budgets per team.

2. **When would you choose hard multi-tenancy (separate Prometheus instances) over soft multi-tenancy (shared instance)?**

   *Answer core points*: Hard multi-tenancy when: (1) Different business units with conflict of interest, (2) Vastly different scale (small auth vs large payments), (3) Compliance/PCI-DSS requires data isolation, (4) Noisy neighbor risk (one team's cardinality explosion affects others). Soft multi-tenancy when: shared responsibility, similar scale, no compliance requirements, cost optimization priority.

3. **What's your observability disaster recovery strategy? What's your RPO and RTO?**

   *Answer core points*: IaC-deployed stack in DR region (Terraform). Critical state: Prometheus metrics via Thanos S3 (cross-region replicated), OpenSearch via S3 snapshots. RPO: < 5 minutes (current data lost during failover). RTO: < 30 minutes (automated IaC deployment). Non-critical: Jaeger traces re-sampleable from Kafka. Grafana dashboards version-controlled in Git. Recovery procedure documented and tested quarterly.

4. **How do you prevent observability cost from growing linearly with traffic?**

   *Answer core points*: (1) Tail sampling: keep errors + slow traces, sample successes. (2) ILM: hot/warm/cold/delete lifecycle on logs and traces. (3) Thanos S3 offload for Prometheus metrics (71% cheaper than EBS). (4) Metric aggregation: recording rules pre-compute expensive queries. (5) Drop unnecessary metrics/labels at the Collector (filter processor). (6) Chargeback to teams — cost visibility incentivizes efficient instrumentation.

5. **Write an ADR for choosing between self-hosted OpenSearch and AWS OpenSearch Service.**

   *Answer core points*: Options: (1) AWS OpenSearch Service: managed, ~30% premium. (2) Self-hosted on EC2: full control, lower unit cost, higher ops. Decision: Self-hosted for log volume > 200 GB/day (cost savings > ops cost). Managed for smaller volumes or when ops team bandwidth is constrained. Trade-off: ops complexity vs cost. Review quarterly as volumes and team size change.

---

## Epilogue: The Observability Mindset

Observability is not a technology stack. It is an engineering discipline.

The stack (Prometheus, Jaeger, OpenSearch, Grafana, OTel) is implementation. The discipline is:

1. **Assume failure.** Distributed systems fail in ways no one predicted. Instrument as if you'll debug a novel failure at 3 AM — because you will.

2. **Correlate signals.** A metric spike without a trace is a mystery. A trace without logs is a diagram. Together they tell the complete story.

3. **Budget for observability.** From the first line of code. Not as an afterthought when the first incident reveals you have no debugging data.

4. **Instrument for others.** The engineer debugging your service at 3 AM might not be you. Make their job possible.

5. **Trade off consciously.** Every design decision (head vs tail sampling, managed vs self-hosted, soft vs hard multi-tenancy) has a justification. Write it down. Share it. Review it.

6. **Observability is a feature.** Your users don't see it, but they experience it every time an incident is resolved in 5 minutes instead of 5 hours.

---

**End of Observability Curriculum.**
