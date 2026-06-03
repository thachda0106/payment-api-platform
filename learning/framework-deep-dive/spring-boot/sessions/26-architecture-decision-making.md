# Session 26: Architecture Decision-Making

## 1. Why This Topic Exists

Most architecture failures are not technical failures. They are decision failures. A team chooses MongoDB because it was a trending topic at a conference. They choose microservices because Netflix does. They choose Kafka because someone said "event-driven architecture" in a design review. Six months later, they have a distributed monolith, a $20K/month cloud bill, and a Postgres instance that would have handled the load fine.

At Staff level, your primary output is NOT code. Your primary output is decisions — and the frameworks, processes, and records that make those decisions durable. A Staff engineer who ships excellent code but makes poor architecture decisions is a net negative to the organization. A Staff engineer who makes excellent decisions but ships average code is a force multiplier.

**Staff engineer insight**: The hardest architecture decision is not choosing between technologies. It is choosing what NOT to build, what NOT to optimize, and what NOT to worry about — right now.

## 2. Mental Model

```
Architecture Decision Quality = f(Rigor × Reversibility × Stakeholder Alignment × Documentation)

NOT Architecture Decision = "I read a blog post and now I'm convinced we need event sourcing"
```

### The Decision Temperature Framework

Every decision has a "temperature" — how irreversible it is and how fast it must be made:

| Temperature | Definition | Process | Example |
|-------------|-----------|---------|---------|
| **Type 1** (Two-Way Door) | Easily reversible, low cost of reversal | Decide fast, document briefly | Which JSON library to use |
| **Type 2** (Heavy Door) | Reversible but with significant cost | Lightweight ADR, 1-2 reviewers | Choosing PostgreSQL vs MySQL |
| **Type 3** (One-Way Door) | Irreversible or extremely costly to reverse | Full ADR, architecture review, board approval | Monolith vs microservices split |

Jeff Bezos called these "Type 1" (two-way door) and "Type 2" (one-way door) decisions. The insight: most organizations treat ALL decisions as Type 2, slowing everything down. Most decisions are actually Type 1 — you can reverse them quickly. Reserve the heavy process for Type 2 decisions.

### The Tragic Quadrant

```
          High Impact
              │
     Dangerous│ Critical
     (fast,   │ (thorough,
      high    │  high
      risk)   │  process)
              │
Low Urgency ──┼─── High Urgency
              │
     Trivial  │ Panic
     (ignore, │ (fast,
      low     │  high
      process)│  risk)
              │
          Low Impact
```

The "Panic" quadrant is where most production incidents live — high urgency, high impact. The "Dangerous" quadrant is where architecture decisions live — low urgency, high impact, but often made too casually because there's no fire.

## 3. Internal Architecture

### Architecture Decision Records (ADR)

An ADR is a lightweight document that captures a significant architecture decision, the context in which it was made, the options considered, and the consequences. It is NOT a design document (which describes HOW). It is a decision log.

#### ADR Template

```markdown
# ADR-{NNN}: {Short Title of Decision}

**Status**: [Proposed | Accepted | Deprecated | Superseded by ADR-XXX]

**Date**: YYYY-MM-DD

**Deciders**: [List of people involved in the decision]

## Context
Describe the forces at play: technical, business, organizational, temporal.
What problem are we solving? What constraints exist?
Why can't we just do the obvious thing?

## Decision
State the decision in one sentence. Be explicit.
"We will use X to do Y because of Z."

## Options Considered
### Option 1: {Name}
- **Description**: What it is
- **Pros**: 
- **Cons**: 
- **Why not chosen**: 

### Option 2: {Name}
(same structure)

### Option 3: {Name}
(same structure)

## Consequences
### Positive
- What becomes easier
- What risks are mitigated
- What capabilities are gained

### Negative
- What becomes harder
- What risks are introduced
- What technical debt is accepted

### Neutral
- What changes but doesn't clearly help or hurt

## Confirmation
How will we know this decision was correct?
- Metrics we will monitor
- Gates we must pass
- Date for re-evaluation
```

#### When to Write an ADR

Write an ADR when:
1. The decision will affect more than one team
2. The decision is hard to reverse
3. Three or more engineers are debating the right approach
4. You're choosing a new technology to add to the stack
5. You're changing a cross-cutting architectural pattern
6. Someone 2 years from now will ask "why did they do it this way?"

Do NOT write an ADR for:
1. Choosing a library within an already-approved category (e.g., which JSON parser)
2. Code organization decisions within a single team
3. Implementation details that don't cross module boundaries
4. Temporary workarounds (these are comments, not ADRs)

#### ADR Storage and Discovery

```
docs/arch/adr/
├── README.md              ← Index of all ADRs with status and links
├── 001-use-postgresql.md
├── 002-use-jooq-for-reads.md
├── 003-external-payment-provider-strategy.md (Status: Superseded by ADR-012)
├── 004-event-sourcing-for-orders.md
├── 005-authentication-via-oauth2.md
├── ...
├── 012-external-payment-provider-v2.md (Status: Accepted)
└── template.md            ← The canonical ADR template
```

### Decision Framework

```
For each significant decision, follow this framework:

1. DEFINE THE PROBLEM
   ├── What is the exact problem we're solving?
   ├── Is it a real problem or a perceived one?
   ├── Who experiences this problem?
   └── What happens if we do nothing?

2. IDENTIFY CONSTRAINTS
   ├── Technical: language, existing tech stack, cloud provider, skillset
   ├── Business: time, budget, compliance, SLA requirements
   ├── Organizational: team size, Conway's Law, hiring pipeline
   └── Temporal: deadline, roadmap commitments

3. GENERATE OPTIONS
   ├── Option A: The obvious/default choice
   ├── Option B: The ambitious/innovative choice
   ├── Option C: The "do nothing" or minimal change choice
   └── Option D: The hybrid/short-term-to-long-term path

4. ANALYZE TRADE-OFFS
   ├── For each option, evaluate against constraints
   ├── Identify: What do we GAIN? What do we LOSE?
   ├── Consider second-order effects: "What happens AFTER we choose this?"
   └── Document assumptions — what would make this evaluation wrong?

5. MAKE THE DECISION
   ├── Choose ONE option (NOT a committee compromise)
   ├── Name the decider (not "the team decided")
   └── If you can't decide: who has the authority? Escalate.

6. DOCUMENT CONSEQUENCES
   ├── Write the ADR
   ├── List what becomes easier
   ├── List what becomes harder
   ├── Set a re-evaluation trigger or date
   └── Store in the ADR repository

7. COMMUNICATE
   ├── Team: Full context, technical depth
   ├── Management: Business impact, timeline, risk
   ├── Adjacent teams: How this affects them, what contracts change
   └── Future engineers: The ADR is their primary artifact
```

### Evaluating Trade-offs

**Framework for comparison**:

| Dimension | What to Ask | Weight |
|-----------|------------|--------|
| Simplicity | How many moving parts? How much cognitive load? | High |
| Flexibility | Can we change direction later? How easily? | Medium |
| Performance | Does it meet our throughput/latency requirements? | Must-pass |
| Operability | Can we debug, monitor, deploy this easily? | High |
| Team capability | Do we have the skills? Can we hire for it? | Must-pass |
| Cost | License fees? Cloud costs? Developer time? | Medium |
| Ecosystem | Libraries, tools, community, support? | Medium |
| Risk | What's the worst that could happen? | Must-assess |

#### Common Trade-off Pairs

| Trade-off | The Tension | How to Decide |
|-----------|------------|---------------|
| Latency vs Consistency | Faster responses vs correct data | Strong consistency when data corruption is unacceptable (payments, inventory). Eventual consistency when user experience is paramount (social feeds, analytics). |
| Simplicity vs Flexibility | Easy to understand vs easy to change | Start with simplicity. Add flexibility when you have concrete evidence you need it, never before. |
| Build vs Buy | Full control vs faster time-to-market | Buy commodity (auth, payments, monitoring, email). Build differentiation (core domain logic). |
| Short-term vs Long-term | Ship faster vs sustainable architecture | When team < 10 and runway < 6 months: short-term wins. When team > 20 or product is mature: long-term wins. |
| Performance vs Maintainability | Optimized code vs readable code | Profile first. Only optimize proven bottlenecks. Document WHY the optimization exists. |
| Monolith vs Microservices | Simple deployment vs independent scaling | Start with monolith (modular). Extract microservices when you have: independent scaling needs, separate team ownership, or different deployment frequencies. |

### Reversible vs Irreversible Decisions

From Jeff Bezos's 2015 shareholder letter:

> "Some decisions are consequential and irreversible or nearly irreversible — one-way doors — and these decisions must be made methodically, carefully, slowly, with great deliberation and consultation. If you walk through and don't like what you see on the other side, you can't get back to where you were before. We can call these Type 1 decisions. But most decisions aren't like that — they are changeable, reversible — they're two-way doors. If you've made a suboptimal Type 2 decision, you don't have to live with the consequences for that long. You can reopen the door and go back through."

**Process differences**:

| Aspect | Two-Way Door | One-Way Door |
|--------|-------------|--------------|
| Time to decide | Hours to days | Days to weeks |
| Approvals needed | Self or team lead | Architecture review + board |
| Documentation | Commit message or ADR lite | Full ADR with consequence analysis |
| Re-evaluation | "We'll change if it hurts" | Scheduled formal review at 3, 6, 12 months |
| Failure tolerance | High — we can undo it | Low — we must get it right |

**Examples of irreversible decisions in Spring Boot context**:
- Choosing the primary database paradigm (SQL vs document vs graph)
- Defining bounded context boundaries (very expensive to change later)
- Picking the integration pattern between services (REST vs events vs gRPC)
- Selecting the cloud provider (or multi-cloud strategy)
- Choosing the identity/authentication protocol (OAuth2 vs SAML)

## 4. Runtime Behavior

### How Decisions Evolve During a Project

```
Month 0:  "Let's use Spring Boot with JPA, it's the default"
Month 3:  "JPA is generating terrible queries for our reporting. Let's add jOOQ."
Month 6:  "We now have JPA entities AND jOOQ-generated classes. Double maintenance."
Month 9:  ADR-007: "Deprecate JPA for writes, use jOOQ universally"
Month 12: JPA removed from the project.
```

The pattern: a default decision (JPA) was made without analysis. It was correct for simple CRUD but wrong for complex queries. The team spent 6 months maintaining two data access layers before making the correct decision.

**Lesson**: The "obvious default" is sometimes wrong. Spend 1 day analyzing whether the default fits your product BEFORE spending 6 months undoing it.

### Decision Velocity

The speed at which decisions are made is a critical, often overlooked, architecture metric:

```
Decision Velocity = Number of decisions made / Time

High Decision Velocity:
  - Two-way doors are decided by individuals or small groups in hours
  - One-way doors get dedicated ADRs and architecture review
  - Bottleneck is review, not decision-making
  
Low Decision Velocity:
  - Every decision requires a meeting
  - Meetings require everyone's presence
  - Everyone's presence requires scheduling (2 weeks wait)
  - Result: Innovation grinds to a halt
```

**Staff engineer's role**: Reduce the cost of reversible decisions so that the organization defaults to action. Increase the quality of irreversible decisions so that the organization has confidence.

## 5. Request Flow Diagrams

### Decision-Making Request Flow

```
Developer identifies architecture question
  │
  ├─ Is it a Type 2 (two-way door) decision?
  │   ├─ YES → Developer decides, documents in commit message or Slack
  │   │         └─ If wrong: we notice in code review or testing, we fix it
  │   └─ NO → Is it a Type 1 (one-way door) decision?
  │             ├─ YES → Follow formal process:
  │             │   ├─ 1. Write problem statement
  │             │   ├─ 2. Generate options
  │             │   ├─ 3. Analyze trade-offs
  │             │   ├─ 4. Draft ADR
  │             │   ├─ 5. Present to architecture review
  │             │   │     └─ If complex: RFC period (1 week)
  │             │   ├─ 6. Architecture board approval
  │             │   ├─ 7. Finalize ADR (status: Accepted)
  │             │   └─ 8. Communicate to stakeholders
  │             └─ MAYBE → Escalate to Staff engineer or architect for classification
  │
  └─ Decision recorded in ADR repo
       └─ Re-evaluation scheduled (if specified)
```

## 6. Lifecycle Diagrams

### Decision Lifecycle

```
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│  1. TRIGGER                                                     │
│     - New feature requires technology choice                    │
│     - Existing solution isn't working                           │
│     - Team disagrees on approach                                │
│     - Scaling limit reached                                     │
│                                                                │
│  2. ANALYSIS                                                    │
│     - Problem definition                                        │
│     - Constraints identification                                │
│     - Options generation (3-5 viable options)                   │
│     - Trade-off analysis                                        │
│     - Risk assessment                                           │
│                                                                │
│  3. PROPOSAL                                                    │
│     - ADR drafted with status: Proposed                         │
│     - RFC period (1 week for major decisions)                   │
│     - Stakeholder review                                        │
│     - Architecture review meeting                               │
│                                                                │
│  4. DECISION                                                    │
│     - Approved → ADR status: Accepted                           │
│     - Rejected → ADR status: Rejected (with reason documented)  │
│     - Deferred → ADR status: Proposed, with next review date    │
│                                                                │
│  5. COMMUNICATION                                               │
│     - Team: detailed walkthrough                                │
│     - Management: executive summary                             │
│     - Adjacent teams: impact analysis                           │
│     - Documentation: ADR published + indexed                    │
│                                                                │
│  6. EXECUTION                                                   │
│     - Breaking down into tasks                                  │
│     - Implementation                                            │
│     - Validation (metrics, tests, gates)                        │
│                                                                │
│  7. RE-EVALUATION                                               │
│     - Scheduled review (3, 6, 12 months as specified)           │
│     - Check: Are consequences as expected?                      │
│     - Check: Have constraints changed?                          │
│     - If decision is wrong: write ADR to reverse or replace      │
│     - ADR status → Superseded by ADR-XXX or Deprecated          │
│                                                                │
│  8. ARCHIVE                                                     │
│     - ADR remains in repo forever (read: reasons for decisions) │
│     - Status: Superseded or Deprecated                          │
│     - New engineers can read the history and understand WHY     │
└────────────────────────────────────────────────────────────────┘
```

## 7. Source Code Reading Guide

For architecture decision-making, the "source code" is not code. It is prior decisions, system documentation, and the architecture of existing successful systems.

### Real-World ADR Repositories to Study

1. **AWS Architecture Blog / AWS Well-Architected Framework**
   - Not ADRs per se, but decision analysis at scale
   - Study: "How AWS chooses between DynamoDB and RDS for a given workload"

2. **GitHub's public engineering blog**
   - Their database partitioning decision, GraphQL migration, MySQL → Vitess journey
   - Excellent examples of trade-off analysis

3. **Uber's engineering blog**
   - Microservice extraction, event sourcing, database migrations at scale
   - Shows: how decisions that were correct at 100 engineers became wrong at 2000 engineers

4. **Netflix Tech Blog**
   - Chaos engineering, CDN strategy, microservice architecture evolution
   - Shows: how platform companies make infrastructure decisions

5. **The ADR GitHub organization** (`github.com/adr`)
   - `adr-tools` — command-line tool for managing ADRs
   - Reference implementations and templates

### Books Worth Reading (Not for This Session, But for Career)

- *The Architecture of Open Source Applications* — architects of major open-source projects explain their decisions
- *Designing Data-Intensive Applications* (Kleppmann) — the decision framework for data systems
- *Building Evolutionary Architectures* (Ford, Parsons, Kua) — how to make reversible decisions
- *Domain-Driven Design* (Evans) — how to decide bounded context boundaries
- *Team Topologies* (Skelton, Pais) — how organizational structure shapes architecture decisions

## 8. Production Failure Scenarios

### Scenario 1: HIPPO-Driven Architecture Decision Destroys a Quarter

**Symptom**: The CTO read that microservices are the future and mandates a microservices migration for the monolith.

**Root cause**: The Highest Paid Person's Opinion (HIPPO) overrode technical analysis. The decision was made without: problem definition ("what problem are we solving?"), trade-off analysis, or acknowledgment of the cost.

**Consequences**:
- 6 months of migration work, zero feature delivery
- Distributed monolith: services that cannot deploy independently
- Network failures that didn't exist in the monolith
- 3x the infrastructure cost
- 2 senior engineers quit

**How a Staff engineer could have prevented this**: Write an ADR that frames the decision properly before the HIPPO declares it. Present trade-offs in business terms: cost, timeline, risk. Propose the modular monolith as a safer intermediate step. "Let's prove we can modularize within a single deployable before adding network boundaries."

### Scenario 2: Technology Chosen Without Considering Operational Maturity

**Symptom**: Team chooses Apache Cassandra for a new service because "it scales write-heavy workloads." The team has 4 engineers, none with Cassandra operations experience.

**Root cause**: Decision considered only technical fit (Cassandra IS good for write-heavy workloads) but not operational fit (nobody knows how to run Cassandra in production).

**Consequences**:
- Cassandra cluster has weekly outages due to misconfigured compaction
- Repair operations run during peak hours, causing latency spikes
- No one understands why read-repair is causing query timeouts
- 3 months later: migration to PostgreSQL, which would have been fine with read replicas

**Staff engineer prevention**: Add "Operational Capability" as a weighted constraint in the decision framework. If no one on the team has operated the technology in production, weight it heavily against adoption. Either hire the expertise first or choose a different technology.

### Scenario 3: Resume-Driven Development (RDD)

**Symptom**: Every new project uses a different stack: one uses Kotlin + Ktor, another uses Scala + Akka, another uses Go, another uses Rust. Each choice was "the right tool for the job."

**Root cause**: Engineers choosing technologies that look good on their resume, not technologies that are best for the organization's long-term maintainability.

**Consequences**:
- 7 programming languages in production across 12 services
- No shared libraries, no shared patterns, no shared tooling
- On-call rotation is a nightmare — you can't debug a Go service if you know only Kotlin
- Hiring pipeline is unfocused
- Every service needs separate CI/CD, monitoring, and security scanning setup

**Staff engineer prevention**: Limit "innovation tokens" per project. A project can introduce ONE new significant technology. If you want to use a new language, everything else must be standard. If you want a new database, the language must be standard. This forces explicit prioritization of what to innovate on.

## 9. Debugging Techniques

### Debugging Architecture Decisions

When an architecture decision has gone wrong, use these techniques to understand why:

**Technique 1: The Five Whys**
```
Problem: Orders service is down again.
Why? → The database connection pool is exhausted.
Why? → Reporting queries are running on the primary database.
Why? → We didn't set up read replicas.
Why? → The architecture decision 18 months ago said "we'll add replicas when we need them."
Why didn't we add them? → No one was alerted that we "needed them."
Root cause: The decision relied on an unmonitored trigger condition.
```

**Technique 2: Decision Post-Mortem**
```
When a decision turns out to be wrong, run a blame-free post-mortem:

1. What was the decision?
2. What information was available at the time?
3. What information changed after the decision?
4. What assumptions were wrong?
5. What process should have caught this?
6. How do we prevent similar decisions from going wrong?
```

**Technique 3: Architecture Fitness Functions**
```java
// Use ArchUnit or custom fitness functions to verify architecture decisions are maintained
@Test
void transactionManagerShouldBeJdbcTransactionManager() {
    // ADR-008 decided we use JDBC transaction manager, not JTA
    assertThat(ApplicationContext.getBean(PlatformTransactionManager.class))
        .isInstanceOf(DataSourceTransactionManager.class);
}

@Test
void boundedContextsMustNotDependOnEachOthersCore() {
    // ADR-003 decided bounded context separation
    noClasses()
        .that().resideInAPackage("..ordering.core..")
        .should().dependOnClassesThat()
        .resideInAPackage("..billing.core..")
        .check(importedClasses);
}
```

### Debugging Decision Paralysis

When a team is stuck unable to decide:

1. **Timebox**: "We will decide by Friday 5 PM. If we haven't made a decision by then, we go with [default option]."
2. **Narrow options**: If you have 7 options, you have not done your analysis. Cut to top 3.
3. **Reversible first**: Can we make a reversible version of this decision? Do that now, defer the irreversible part.
4. **Worst case analysis**: "What's the worst that happens if we choose option A and it's wrong?" If the answer is survivable, choose A.
5. **Disagree and commit**: Not everyone will agree. Once the decision is made, everyone commits to making it work. No passive resistance.

## 10. Observability Considerations

### Observing the Health of Architecture Decisions

Just like you monitor service health, monitor decision health:

| Signal | Measurement | Alert Threshold |
|--------|------------|-----------------|
| Decision latency | Time from proposal to decision | > 2 weeks for two-way doors |
| Decision reversal rate | ADRs with status "Superseded" within 6 months | > 30% (decisions are being made too casually) |
| Undocumented decisions | Architecture choices without ADRs | > 0 in production path |
| Technology diversity | Number of distinct languages/databases/frameworks | > 3 per team size of 10 |
| ADR staleness | ADRs not reviewed in the last 12 months | > 50% of active ADRs |
| Team satisfaction | Survey: "I understand why we made our architecture choices" | Score < 7/10 |

### Decision Observability Dashboard

Build a simple dashboard or Wiki page:

```
| ADR # | Title | Status | Date | Next Review | Health |
|-------|-------|--------|------|-------------|--------|
| 001 | Use PostgreSQL | Accepted | 2022-01 | 2025-01 | 🟢 Healthy |
| 002 | Use jOOQ for reads | Superseded by 015 | 2022-03 | N/A | ⚫ Archived |
| 003 | Payment provider strategy | Superseded by 012 | 2022-06 | N/A | ⚫ Archived |
| 004 | Event sourcing for orders | Proposed | 2024-01 | N/A | 🟡 Stale (no decision in 45 days) |
| ... | ... | ... | ... | ... | ... |
```

## 11. Performance Implications

### The Performance Cost of Poor Architecture Decisions

| Decision | Performance Impact | Real-World Example |
|----------|-------------------|-------------------|
| Synchronous communication between microservices | p50 latency = sum of all service latencies; p99 = worst of a long tail | A request that fans out to 5 services: each 10ms p50 → 50ms; each 100ms p99 → any one of the 5 can hit 500ms, making the p99 500ms |
| No read replicas for reporting queries | Reporting queries consume connection pool, block user-facing queries | Black Friday: finance team runs monthly report → all customer orders timeout |
| Using JPA for OLAP queries | N+1 SELECTs, loading full entity graphs for aggregate queries | Dashboard page loads 15 seconds, loads 50,000 entities to display 5 numbers |
| Ignoring database connection pool sizing | Connection storm at startup, connection exhaustion at peak | HikariCP default (10) is fine for dev. Production with 100 concurrent requests: bottlenecked at 10 DB connections |
| Not implementing caching for read-heavy endpoints | Database CPU at 80% serving identical queries | Same product page, queried 1000x/second, same query every time, no cache layer |

### Quantifying Architecture Decisions in Performance Terms

Before making a decision that will affect performance, write a "performance budget" ADR:

```markdown
# ADR-009: Performance Budget for Order Creation API

## Performance Budget
- p50 latency: < 50ms
- p95 latency: < 200ms
- p99 latency: < 500ms
- Throughput: 1000 requests/second per instance

## Implications for Architecture Decisions
- Any synchronous call to an external service must complete in < 20ms p95
- Any database query must complete in < 10ms p95 (allowing for connection overhead)
- This budget leaves ~30ms for business logic
- If any component cannot meet its budget, we must either:
  - Optimize it
  - Make it asynchronous
  - Re-evaluate the budget with product management

## Monitoring
- Micrometer Timer metric: "order.create.latency"
- Dashboards: Grafana panel tracking p50/p95/p99
- Alert: p95 > 200ms for 5 minutes
```

## 12. Architecture Implications

### Second-Order Effects

Every architecture decision has second-order effects that are more impactful than the direct effect:

| Decision | Direct Effect | Second-Order Effect |
|----------|--------------|-------------------|
| Adopt microservices | Independent deployments | Need for: service discovery, distributed tracing, circuit breakers, API versioning, contract testing, event schemas, eventual consistency handling, distributed saga patterns |
| Choose MongoDB over PostgreSQL | Flexible schema | No joins, no transactions (before 4.0), different indexing model, different query language, different operational knowledge required, different backup/recovery model |
| Use Kafka for service communication | Async, durable messaging | Need for: schema registry, consumer group management, offset management, dead letter queues, exactly-once semantics handling, partition rebalancing |
| Adopt event sourcing | Full audit trail, temporal queries | Need for: event schema evolution, snapshotting, CQRS read models, eventual consistency, event versioning, upcasters, replay infrastructure |
| Use Spring Cloud | Service discovery, config server, gateway | Vendor lock-in to Spring ecosystem, operational complexity, version compatibility matrix, increased startup time |

### Architecture Decision Anti-Patterns

#### 1. Resume-Driven Development (RDD)
Choosing technologies because they look good on a resume, not because they solve the business problem. **Detection**: The justifications for technology choices mention "market demand" or "modern" more than "solves our problem."

#### 2. Analysis Paralysis
Spending so much time analyzing options that no decision is made. **Detection**: The same discussion has occurred in 3+ meetings with no forward progress. **Fix**: Timebox to one week, then force a decision.

#### 3. HIPPO-Driven Decisions
The Highest Paid Person's Opinion overrides technical analysis. **Detection**: The decision was made in a meeting where the most senior person spoke first and no one disagreed. **Fix**: Require written proposals before discussion.

#### 4. Cargo Culting
"Doing what Netflix/Google/Amazon does" without understanding their constraints. **Detection**: "Netflix uses microservices, so we should too" — but Netflix has 3000 engineers and you have 15. **Fix**: Every decision must be justified by YOUR context, not someone else's.

#### 5. Golden Hammer
Using a familiar technology for every problem. **Detection**: "We'll use Kafka" for a problem that needs a simple queue. "We'll use Kubernetes" for a single-server app. **Fix**: Generate at least 3 options for every decision.

#### 6. Technology Over Solution
Focusing on which technology to use before defining the problem. **Detection**: "Should we use Kafka or RabbitMQ?" before "What are our messaging requirements?" **Fix**: Define the problem and constraints first, then evaluate technologies.

#### 7. Sunk Cost Fallacy
Continuing with a poor decision because you've already invested in it. **Detection**: "We've already spent 3 months on this migration, we can't stop now." **Fix**: Evaluate the decision as if you were starting fresh today. What would you choose?

## 13. Team Ownership Implications

### How Staff Engineers Own Decisions

Staff engineers don't make architecture decisions in isolation. They facilitate the decision process:

| Activity | Senior Engineer | Staff Engineer |
|----------|----------------|---------------|
| Identifying decisions needed | "I need to make a decision about X" | "Our team's next 3 architecture decisions are X, Y, Z. Here's the timeline and format for each." |
| Gathering context | Asks their team | Interviews stakeholders across 3+ teams |
| Generating options | Proposes the obvious option | Generates 3-5 options with trade-off analysis |
| Making the decision | Makes it or asks tech lead | Facilitates the decision, ensures the right person decides |
| Documenting | Writes a commit message | Writes an ADR with full context |
| Communicating | Mentions in standup | Presents to team, management, adjacent teams |
| Following up | Moves on to next task | Schedules re-evaluation, monitors consequences |

### Decision Ownership Matrix

```
                       DACI Model
                       
    Driver:    Who drives the decision process? (Staff Engineer)
    Approver:  Who has final say? (Architecture Board / CTO)
    Consulted: Who provides input? (Domain experts, affected teams)
    Informed:  Who needs to know? (Rest of engineering org)
```

### Communicating Decisions

**To the Team** (technical depth):
- Full ADR walkthrough
- Code examples of the new pattern
- Migration plan for existing code
- FAQ: "What do I do if X?"

**To Management** (business impact):
- What: The decision we made
- Why: The problem it solves
- Cost: Money, time, risk
- Timeline: When it's done
- Blockers: What we need from them

**To Adjacent Teams** (interface impact):
- What changes in the API/interface they consume
- Timeline for the change
- Migration path / backward compatibility
- Point of contact for questions

**To Future Engineers** (the ADR):
- The ADR is the artifact for engineers who join in 2 years
- It must answer: "Why did they do it this way?" without requiring tribal knowledge

## 14. Interview Questions

### Question 1: "You're the Staff engineer on a 15-person team building a financial trading platform. The team is split between using PostgreSQL and using a time-series database (TimescaleDB/InfluxDB) for trade data. Walk me through your decision process."

**Staff-Level Answer**:

Step 1: Define the problem precisely. "We need to store trade data" is not precise enough. I need to know:
- Write throughput: how many trades per second?
- Read patterns: what queries are run? Point lookups? Time-range scans? Aggregations?
- Data retention: how long do we keep data? What happens after that?
- Consistency requirements: is it acceptable to see data that's 5 seconds stale?
- Query latency requirements: p50, p99?

Step 2: Identify constraints:
- Team skills: the team knows PostgreSQL. They don't know TimescaleDB or InfluxDB operations.
- Compliance: financial data has regulatory retention requirements (likely 7+ years)
- Existing infrastructure: already running PostgreSQL for user data, accounts, positions
- Operational maturity: we have PostgreSQL monitoring, backup, failover procedures

Step 3: Generate options:
- Option A: PostgreSQL with partitioned tables (monthly partitions on trade_date)
- Option B: TimescaleDB (PostgreSQL extension — same operational model, time-series optimized)
- Option C: InfluxDB (separate database, different operational model)
- Option D: PostgreSQL now, migrate to specialized solution when we hit scaling limits

Step 4: Trade-off analysis:
- PostgreSQL: zero new operational complexity, team knows it, but time-range queries on billions of rows will eventually require careful partition management and potentially BRIN indexes
- TimescaleDB: same PostgreSQL operational model, automatic partitioning (hypertables), built-in compression, continuous aggregates — lowest incremental cost
- InfluxDB: best raw performance for time-series, but entirely new operational stack (monitoring, backup, failover, deployment), team must learn new query language (Flux)

Step 5: Decision: Option B — TimescaleDB.
- It's a PostgreSQL extension, so it inherits all our existing operational knowledge
- Hypertables give us automatic time-based partitioning without manual partition management
- Compression policies handle data aging automatically
- Continuous aggregates replace the need for materialized views
- If we're wrong, migrating FROM TimescaleDB TO plain PostgreSQL is reversible (data is in PostgreSQL format)

Step 6: ADR documents the decision, including the re-evaluation trigger: "If trade volume exceeds 50K/second or we discover a TimescaleDB limitation, re-evaluate with a specialized time-series database."

The meta-lesson: the decision framework considers not just what's technically best, but what the team can OPERATE well, what's reversible, and what has the lowest migration cost if we're wrong.

---

### Question 2: "Describe a situation where you would advocate AGAINST using microservices. Be specific about the triggers that would make you push for a monolith instead."

**Staff-Level Answer**:

I advocate against microservices in most situations. The burden of proof should be on microservices, not on the monolith. Here are the specific triggers where I push for monolith:

**Trigger 1: Team size < 20 engineers.** With fewer than 20 engineers, you can understand the entire monolith codebase. The communication overhead of microservices (API versioning, contract testing, integration testing, deployment coordination) exceeds the benefit of independent teams.

**Trigger 2: No independent scaling need.** If the application's load is uniform across features — all parts scale together — there is no technical reason to decompose. Add more instances of the monolith. Horizontal scaling works for monoliths too.

**Trigger 3: Product is not yet stable.** If requirements are changing rapidly (pre-product-market-fit), microservices boundaries will be wrong, and wrong boundaries are worse than no boundaries. A monolith allows you to refactor across module boundaries freely. Microservices freeze the boundaries at the worst possible time — when you understand the domain the least.

**Trigger 4: No team autonomy requirement.** If one team owns the entire application, they don't need independent deployment. Microservices primarily solve the organizational scaling problem (multiple teams), not the technical scaling problem.

**Specific counter-example**: I worked with a startup where the founding team (4 engineers) built a microservices architecture from day one. They had 12 services. Every feature change touched 3-4 services. Deployment required coordinating 4 CI/CD pipelines. End-to-end testing was impossible. The CTO's justification was "Netflix uses microservices." Netflix has 3000+ engineers and a platform team that builds the infrastructure those microservices run on.

**What I push for instead**: A modular monolith with compile-time enforced module boundaries (Gradle multi-module). Each module has the same isolation guarantees as a microservice (no direct dependency on another module's internals) but runs in a single process, single deployable. If and when a module genuinely needs independent scaling or team autonomy, extract it — but only then.

**The measurement**: If you are spending more time on distributed systems problems (network failures, partial failures, eventual consistency, distributed tracing, schema compatibility) than on business logic, your microservices decomposition is wrong. You have a distributed monolith.

---

### Question 3: "Your CTO comes back from a conference and announces: 'We're moving everything to event-driven architecture using Kafka.' The team has never used Kafka. Walk me through how you handle this."

**Staff-Level Answer**:

This is a classic HIPPO-driven decision with high risk. My approach:

**Phase 1: Understand the Motivation (1 day)**
I schedule a 1:1 with the CTO. Not to argue — to understand. "Help me understand what problem you're trying to solve with event-driven architecture. What did you see at the conference that made you think this is right for us?" This does two things: it validates the CTO's intent (they're trying to help) and it gets me the problem statement behind the technology prescription.

The answer is usually something like: "Our services are too coupled. When the order service calls the payment service synchronously and payment is slow, orders time out. We need async communication."

**Phase 2: Separate the Problem from the Solution (1 day)**
Now I have the actual problem (synchronous coupling causing latency cascades) and the prescribed solution (Kafka). These are separate things. I prepare a brief (1 page) that says:
- Here's the problem (we agree)
- Here are the options to solve it (Kafka is ONE option)
- Here's what each option requires operationally

**Phase 3: Propose a Staged Approach (1 week)**
"The worst outcome is that we adopt Kafka, hit a production incident because we don't know how to operate it, and blame event-driven architecture as a concept. Let's prove it works first."

Stage 1 (2 weeks): One engineer runs Kafka locally, builds a simple producer/consumer, and documents the development experience. No production commitment.
Stage 2 (1 month): Pick ONE service boundary. Make it async using application events (Spring's ApplicationEventPublisher) within the monolith first. This proves we can design event-driven interfaces without the distributed system complexity.
Stage 3 (2 months): Extract that boundary to a separate deployable WITH Kafka. By this point, we have event schema design experience and one engineer with Kafka development experience.
Stage 4 (Ongoing): Evaluate. Did this actually solve the problem? Is it better than the synchronous approach for THIS boundary? Only then do we consider expanding to other boundaries.

**Phase 4: Address Operational Risk Directly**
I ask: "Who is on-call for Kafka?" If the answer is "the team," I ask: "Which person on the team has debugged a Kafka partition skew at 3 AM?" If no one, we are not ready for production Kafka. We need to either hire someone who has operated Kafka in production or start with a managed service (Confluent Cloud, AWS MSK).

**Phase 5: Write the ADR**
I write an ADR that captures the CTO's insight (the problem IS real) but documents the staged approach. The decision is "Adopt event-driven architecture progressively, starting with in-process events and graduating to Kafka when we have operational maturity." This satisfies the CTO's directive while protecting the team from operational disaster.

The meta-lesson: Never say "no" to leadership. Say "yes, AND here's how we do it safely." Translate their technology prescription into a problem statement, then solve the problem with the approach that minimizes risk.

## 15. Hands-On Exercises

### Exercise 1: Write 3 ADRs for Your Current Project

Choose three architecture decisions that were made recently (or should have been made) in your current project. For each:
1. Write a complete ADR using the template above
2. Include at least 3 options
3. Include quantitative trade-off analysis where possible
4. Set a re-evaluation trigger

If the decision was already made, write the ADR as a retrospective — capture the reasoning that existed at the time, even if it wasn't documented.

### Exercise 2: Decision Post-Mortem

Identify one architecture decision in your current or past project that turned out to be wrong. Run a decision post-mortem:
1. What was the decision?
2. What information was available at the time?
3. What information was missing?
4. What would you have decided differently with today's knowledge?
5. What process change would have caught the wrong decision earlier?

### Exercise 3: Trade-Off Analysis Drill

For the following scenarios, generate 3+ options and analyze trade-offs:
1. Your team needs to add full-text search to a PostgreSQL-based e-commerce app. Options: PostgreSQL full-text search, Elasticsearch, Algolia, Meilisearch.
2. Your monolith needs to send emails. Options: SMTP directly, SendGrid/Mailgun API, AWS SES, on-premise mail server.
3. You need to store user-uploaded files. Options: Database BLOB, filesystem, AWS S3, MinIO (self-hosted S3-compatible).
4. Your API needs rate limiting. Options: Bucket4j (in-memory), Redis-based, API Gateway (Kong/NGINX), CloudFlare/CloudFront.

For each, write a 1-page trade-off analysis with a recommended decision.

### Exercise 4: Run a Mock Architecture Review

With 2-3 colleagues:
1. One person presents an architecture proposal (10 minutes)
2. Reviewers ask clarifying questions (10 minutes)
3. Reviewers identify risks, missing options, flawed assumptions (15 minutes)
4. Group decides: Approve, Reject, or Revise and Re-present
5. Write an ADR for the outcome

Practice the review dynamic: the goal is to improve the decision, not to prove the reviewer is smarter.

## 16. Advanced Challenges

### Challenge 1: Build a Decision Register for Your Organization

Create a centralized ADR repository for your team or organization:
1. Set up the directory structure and template
2. Write the README index
3. Backfill ADRs for the 5 most important past decisions
4. Write a contribution guide (when to write an ADR, how to get it approved)
5. Integrate into code review: "If this PR introduces a new library/pattern, link to the ADR"
6. Set up a quarterly ADR review — stale ADRs are updated or deprecated

### Challenge 2: Implement Decision Fitness Functions

Write ArchUnit tests (or equivalent in your language/framework) that enforce 3-5 architecture decisions from your ADRs. Example: "ADR-003: Bounded contexts must not depend on each other's internals" → ArchUnit test. Integrate into CI. The build fails when an architecture rule is violated.

### Challenge 3: Create an Innovation Token Budget

For your current project, define an "innovation token" budget:
- Count how many new/novel technologies the project uses
- For each, justify: why does this project need THIS technology specifically?
- If the count exceeds 2-3 per project, identify which ones can be replaced with standard alternatives
- Write a policy document: "Maximum 2 innovation tokens per project. Requesting more requires an ADR and architecture review."

### Challenge 4: Retrospective ADRs for a Famous Failure

Pick a well-known engineering failure (Knight Capital's $440M loss in 45 minutes, Healthcare.gov launch, GitLab's database outage, Facebook's 6-hour outage) and write a retrospective ADR for the decision that caused it. Include:
- The context as it was understood at the time
- The options that were available
- Why the wrong option was chosen
- What process or architecture choice would have prevented it
- What the industry learned from this failure

### Challenge 5: Decision-Making Workshop for Your Team

Design and run a 2-hour workshop for your team:
1. 30 min: Teach the decision framework (Type 1/Type 2, trade-off analysis, ADR template)
2. 45 min: Teams of 3-4 work through a realistic scenario, produce an ADR
3. 30 min: Each team presents their ADR, group discusses
4. 15 min: Debrief — "What was hard? What surprised you? What will you do differently?"

The workshop builds the muscle of structured decision-making across the entire team, not just the Staff engineers.
