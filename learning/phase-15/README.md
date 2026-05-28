# Phase 15 — Staff Engineer Level

> **Duration**: Ongoing | **Prerequisites**: Phases 0-14 completed
>
> **Goal**: Lead architecture decisions across multiple teams, evaluate trade-offs at scale, build internal platforms, and grow other engineers.

## 15.1 What Changes at Staff Engineer

Staff Engineer is NOT "Senior Engineer++". It's a different job:
- **Senior**: Builds complex features correctly. Individual execution.
- **Staff**: Decides WHICH features to build. HOW the system should evolve. Multiplies team output.

### Core Staff Skills

1. **Trade-off analysis**: Every decision has pros and cons. Articulate them. Quantify where possible.
2. **ADR writing**: Document decisions for future engineers asking "why did they do it this way?"
3. **Capacity planning**: Given growth projections, when will the system break? Where?
4. **Cost modeling**: How much will this architecture cost at 10x scale?
5. **Technical strategy**: Build vs buy. Make vs integrate. Monolith vs microservices.
6. **Platform engineering**: Build internal tools that make other engineers 10x faster.
7. **Mentoring**: Grow senior engineers into staff engineers.

## 15.2 Trade-Off Analysis Framework

Every architecture decision is a point in a trade-off space. The skill is identifying the DIMENSIONS of the trade-off.

### The Trade-Off Triangle

You can typically optimize for 2 of 3:
- **Consistency** ↔ **Availability** ↔ **Partition Tolerance** (CAP)
- **Throughput** ↔ **Latency** ↔ **Cost**
- **Development Speed** ↔ **Operational Simplicity** ↔ **Feature Completeness**

### Trade-Off Analysis Template

```
Decision: [What are we deciding?]

Dimensions:
| Dimension | Option A | Option B | Option C |
|-----------|----------|----------|----------|
| Performance | ★★★★ | ★★ | ★★★★★ |
| Operational Complexity | ★★ | ★★★★★ | ★ |
| Team Expertise | ★★★★★ | ★★ | ★★★ |
| Cost | ★★★ | ★★★★★ | ★★ |
| Time to Implement | ★★★ | ★★★★★ | ★★ |

Recommendation: Option [X] because [primary dimension] outweighs [secondary dimension].
Risk: [What could go wrong?] Mitigation: [How to handle it?]
```

## 15.3 Architecture Decision Records (ADRs)

### Template

```markdown
# ADR-NNN: Title

- **Status**: Proposed | Accepted | Deprecated | Superseded by ADR-XXX
- **Date**: YYYY-MM-DD
- **Deciders**: Names

## Context
What's the problem? What constraints exist? What's driving this decision?

## Decision
What did we decide? Be specific. Include technology choices, patterns, constraints.

## Consequences
### Positive
What gets easier?

### Negative
What gets harder? What are the new risks?

### Mitigations
How do we address the negative consequences?

## Alternatives Considered
| Alternative | Pros | Cons | Why Rejected |
|-------------|------|------|-------------|

## References
- Links to design docs, Slack threads, meeting notes
```

### When to Write an ADR

- Choosing between 2+ technologies (PostgreSQL vs MySQL for ledger)
- Architectural pattern decisions (Saga vs 2PC for payment orchestration)
- Cross-cutting concerns (How do we handle idempotency across all services?)
- Make vs buy decisions (Build fraud engine vs use third-party)

## 15.4 Capacity Planning

### Back-of-Envelope Estimation

```
DAU = 10,000,000
Txns per user per day = 5
Daily txns = 50,000,000
Peak multiplier = 10x (11 AM, payday)
Peak RPS = 50M × 10 / 86,400 ≈ 5,787 RPS

Per transaction:
- Payment request: 2KB
- Journal entry: 2 lines × 100 bytes = 200 bytes
- Kafka event: 1KB
- Monthly storage: 50M × 30 × 3.2KB ≈ 4.8 TB/month

Payment Service pods at peak:
- Each pod handles 100 RPS (conservative, async I/O)
- Pods needed = 5,787 / 100 ≈ 58
- With 30% buffer: ~75 pods

Kafka:
- Throughput: 5,787 msg/s × 1KB ≈ 5.7 MB/s
- 6 brokers (kafka.m5.large) each handle ~1 MB/s — comfortable margin
```

### Key Questions for Any Capacity Plan

1. What is the bottleneck? (CPU, memory, I/O, network, database connections?)
2. What is the scaling limit? (Can we add more pods? More DB read replicas? More Kafka partitions?)
3. At what point does the architecture break? (Single DB instance maxes out at X TPS)
4. What's the lead time to add capacity? (Minutes for pods, days for DB instances, weeks for new regions)

## 15.5 Cost Modeling

### Payment Platform Monthly Cost Estimate

| Resource | Unit | Qty | Unit Cost | Monthly |
|----------|------|:---:|:---------:|:-------:|
| EKS nodes (m6i.xlarge) | per node | 20 | $150 | $3,000 |
| Aurora (db.r6g.xlarge) | per instance | 3 | $400 | $1,200 |
| MSK (kafka.m5.large) | per broker | 6 | $200 | $1,200 |
| ElastiCache (cache.m6g.large) | per node | 3 | $150 | $450 |
| OpenSearch (m6g.large.search) | per node | 3 | $200 | $600 |
| Data Transfer | per GB | 5000 | $0.09 | $450 |
| S3 (backups, WAL) | per TB | 50 | $23 | $1,150 |
| Support (Business) | 10% of AWS | — | — | $805 |
| **Total (monthly)** | | | | **~$8,855** |
| **10x Scale** | | | | **~$88,500** |

## 15.6 Platform Engineering

Build an **Internal Developer Platform** that makes other engineers 10x faster:

### Golden Path Templates
- `platform new service --name payment-service --language java` → creates full scaffold
- Pre-configured: Dockerfile, K8s manifests, CI/CD pipeline, OTel, health checks, metrics
- New service in production in 10 minutes (not 2 weeks)

### Self-Service Infrastructure
- Developers open a PR to `infra/` repo with a Terraform module reference
- Atlantis auto-plans and applies after approval
- No ticket to Ops team. No waiting.

### Paved Road Documentation
- "How to add a new service" — 10-minute tutorial
- "How to add a new Kafka topic" — 5-minute tutorial  
- "How to deploy to production" — 3-click process
- Everything else is "off-road" — you can do it, but you own it

## 15.7 Mentoring Framework

### From Senior to Staff

| Senior Engineer | Staff Engineer |
|----------------|---------------|
| Executes on assigned work | Identifies WHAT work is most impactful |
| Reviews PRs for correctness | Reviews ARCHITECTURE for soundness |
| Fixes production incidents | Prevents incidents through system design |
| Optimizes code | Optimizes the DEVELOPMENT PROCESS |
| Deep expertise in one area | Broad expertise across multiple areas |

### Mentoring Pattern

1. **Shadow**: They watch you do it. Explain your thinking.
2. **Pair**: Do it together. They drive, you navigate.
3. **Solo with review**: They do it alone. You review before production.
4. **Delegate**: They own it. You're available for questions.

## 15.8 Production Readiness Review Checklist

Before ANY service goes to production, answer:

- [ ] **Observability**: Are logs structured JSON? Are metrics collected? Are traces connected?
- [ ] **Alerting**: Are there alerts for: error rate, latency, saturation? Do alerts have runbooks?
- [ ] **Capacity**: What's the expected load? What's the breaking point? How do we scale?
- [ ] **Dependencies**: What happens if each dependency is down? Circuit breaker? Fallback?
- [ ] **Security**: Are endpoints authenticated? Is RBAC configured? Are secrets in Vault?
- [ ] **Deployment**: Can we deploy without downtime? Can we rollback in < 5 minutes?
- [ ] **Data**: Is there a backup? Has restore been tested? What's the RPO/RTO?
- [ ] **Documentation**: Is there a runbook for common failures? Is there an architecture diagram?
- [ ] **Compliance**: Does this service handle PII? Is it in PCI scope? Audit trail configured?

## 15.9 Exercises

### Ex 15.1 — Write an ADR
Choose a real architecture decision from the payment platform (e.g., "Why use at-least-once with idempotent consumers instead of Kafka exactly-once?"). Write a complete ADR with context, decision, consequences, and alternatives.

### Ex 15.2 — Capacity Plan
Given: 1M DAU, 3 txns/user/day, 15% annual growth. Calculate: peak RPS, storage growth for 3 years, when the current architecture breaks, what to change and when.

### Ex 15.3 — Production Readiness Review
Pick a service from the payment platform. Go through the readiness checklist. Identify 3 gaps. Propose fixes.

### Ex 15.4 — Trade-Off Analysis
Evaluate: "Should we use a shared database for Payment + Refund services, or separate databases?" Use the trade-off analysis template. Write a recommendation with risks and mitigations.

## 15.10 Self-Assessment

- [ ] Can write an ADR that a future engineer can understand and act on
- [ ] Can estimate capacity needs from user metrics (DAU × txns/user × peak multiplier)
- [ ] Can model infrastructure cost at current scale and 10x scale
- [ ] Can lead a production readiness review and identify gaps
- [ ] Understand the difference between Senior and Staff responsibilities
