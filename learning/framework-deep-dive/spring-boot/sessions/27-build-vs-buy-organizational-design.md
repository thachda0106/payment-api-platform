# Session 27: Build vs Buy & Organizational Design

## 1. Why This Topic Exists

Every company has finite engineering capacity. Every hour spent building an internal authentication system is an hour NOT spent building the features that differentiate your product. Every hour spent integrating, customizing, and debugging a third-party payment provider is an hour NOT spent on your core domain. The build vs buy decision is the single highest-leverage allocation decision a Staff engineer makes — it determines whether your team's work compounds into competitive advantage or dissipates into undifferentiated heavy lifting.

Organizational design is the other side of the same coin. Conway's Law states that system architecture mirrors organizational communication structures. Teams that are organized by technology layer will produce layered architectures. Teams organized by business capability will produce bounded contexts. If your organizational structure and your desired architecture disagree, one of them must change — and it is almost always easier to change the organization than to fight Conway's Law.

**Staff engineer insight**: The most impactful architecture decision you will ever make is not a technology choice. It is deciding who works on what, where the team boundaries are, and what contracts govern the interfaces between teams.

## 2. Mental Model

```
Build = Full control + High maintenance cost + Strategic differentiation potential
Buy  = Fast time-to-value + Low maintenance + Commodity capability

Build vs Buy decision = f(Strategic Value, Total Cost of Ownership, Time to Market, Operational Capability)
```

### The Core vs Context Model

Geoffrey Moore's model from *Dealing with Darwin*:

| Category | Definition | Action |
|----------|-----------|--------|
| **Core** | Creates sustainable competitive differentiation. Your customers choose you because of this. | BUILD. Invest heavily. |
| **Context** | Necessary to operate but creates no differentiation. All your competitors do this too. | BUY or minimize investment. |
| **Mission-Critical Context** | Necessary to operate, creates no differentiation, but failure means business failure. | BUY from reliable provider, invest in operational reliability. |
| **Innovation** | Could become core in the future. Experimental. | Build small, evaluate quickly. Kill if it doesn't become core. |

```
Example for a payment processing company:

CORE:
  - Risk scoring / fraud detection engine          ← BUILD (your secret sauce)
  - Payment routing optimization                   ← BUILD (your competitive advantage)
  - Merchant onboarding experience                 ← BUILD (customer-facing differentiation)

MISSION-CRITICAL CONTEXT:
  - Database (PostgreSQL)                          ← BUY (managed service: RDS/Cloud SQL)
  - Identity / Authentication                      ← BUY (Auth0 / Okta / Keycloak)
  - Infrastructure (compute, networking)           ← BUY (AWS/GCP/Azure)
  - Monitoring and observability                   ← BUY (Datadog / Grafana Cloud)
  - CI/CD pipeline                                 ← BUY (GitHub Actions / GitLab CI)

CONTEXT:
  - Internal admin dashboard                        ← BUY or build minimally
  - Employee expense reporting                      ← BUY (SaaS product)
  - Corporate email / calendar                      ← BUY (Google Workspace / O365)
  - Wiki / documentation                            ← BUY (Notion / Confluence)

INNOVATION:
  - AI-based payment anomaly detection              ← BUILD (experiment)
  - Blockchain settlement                           ← BUILD (experiment, likely kill)
```

### Total Cost of Ownership (TCO)

Build TCO includes costs that are easy to forget:

```
Build TCO = Development + Maintenance + Operations + Evolution + Opportunity Cost

                                 3-Year Total Cost Example

BUILD (Internal Auth System):
  Development:      2 engineers × 3 months                  = 6 person-months
  Maintenance:      0.5 engineer × 36 months                = 18 person-months
  Operations:       On-call burden, incident response       = 3 person-months
  Evolution:        New protocols (WebAuthn, passkeys)      = 2 person-months/yr × 3
  Opportunity cost: Those engineers could have built        = ~$1.5M in feature value
                    revenue-generating features
  ---------
  Total:            35 person-months + opportunity cost

BUY (Auth0 / Okta at $0.01 per monthly active user, 100K MAU):
  Integration:      1 engineer × 1 month                     = 1 person-month
  License:          $1,000/month × 36 months                 = $36,000
  Maintenance:      0.1 engineer × 36 months                 = 3.6 person-months
  Operations:       Managed (provider handles)               = 0 person-months
  Evolution:        Included in license (providers add       = 0 person-months
                    WebAuthn, passkeys automatically)
  ---------
  Total:            4.6 person-months + $36,000
```

The build TCO is 7-8x higher in engineering time alone, before accounting for the opportunity cost of those engineers not building revenue-generating features.

### Build vs Buy Decision Matrix

```
                        High Strategic Value
                              │
            BUILD             │           BUILD + BUY HYBRID
            (Invest heavily,  │           (Build core, buy
             full control)    │            surrounding tools)
                              │
Low Maturity ─────────────────┼───────────── High Maturity
  (Few good solutions)        │              (Many good solutions)
                              │
            AVOID / DELAY     │           BUY
            (Wait for market  │           (Don't build
             to mature)       │            commodity)
                              │
                        Low Strategic Value
```

## 3. Internal Architecture

### Build vs Buy Decision Framework

For each potential build decision, apply this analysis:

```
1. STRATEGIC ASSESSMENT
   ├── Is this a core differentiator for our business?
   ├── Would our customers pay more because we built this ourselves?
   ├── Do our competitors use off-the-shelf solutions for this?
   └── If we build this, does it create a sustainable moat?

2. MARKET MATURITY ASSESSMENT
   ├── Are there 3+ viable commercial or open-source solutions?
   ├── Have they been in production at companies our size or larger?
   ├── Is the market consolidating or innovating?
   └── Is pricing transparent and predictable?

3. CAPABILITY ASSESSMENT
   ├── Do we have engineers who have built this before?
   ├── Do we have the operational capacity to run this in production?
   ├── Can we hire for this expertise if we need to grow the team?
   └── Is this where we want to invest our engineering talent?

4. TCO ANALYSIS (3-year horizon)
   ├── Development cost (engineer-months × fully-loaded cost)
   ├── Maintenance cost (ongoing engineering time)
   ├── Operational cost (on-call, incident response, infrastructure)
   ├── Evolution cost (keeping up with standards, security patches)
   ├── License/subscription cost (for buy option)
   ├── Integration cost (for buy option)
   └── Opportunity cost (what we're NOT building)

5. RISK ASSESSMENT
   ├── Build risks: scope creep, key-person dependency, skill gaps
   ├── Buy risks: vendor lock-in, pricing changes, feature gaps, SLA failures
   └── Hybrid risks: integration complexity, version incompatibility

6. DECISION
   ├── BUILD: If strategic value is high AND we have the capability
   ├── BUY: If market is mature AND strategic value is low
   ├── BUILD NOW, BUY LATER: If we need speed but long-term want control
   └── BUY NOW, BUILD LATER: If we need to learn from using a solution first
```

### When to Build

**Signal**: Build when at least 3 of these are true:
1. The capability directly creates competitive differentiation
2. No existing solution adequately addresses your requirements
3. You have unique integration requirements that off-the-shelf solutions cannot meet
4. Compliance or security requirements mandate full control
5. You have the in-house expertise to build and maintain it
6. The build cost is lower than the buy cost over 3 years (including opportunity cost)
7. You need to move faster than any vendor can adapt

### When to Buy

**Signal**: Buy when at least 3 of these are true:
1. The capability is well-understood, standardized, and non-differentiating
2. Multiple stable, well-supported solutions exist
3. The cost is predictable and scales linearly (or better) with usage
4. You don't have (and don't want to build) operational expertise in this domain
5. The capability evolves faster than you can maintain (e.g., auth protocols, email deliverability)
6. The provider's roadmap aligns with your needs
7. The integration cost is significantly lower than the build cost

### Commodity Categories (Almost Always Buy)

| Category | Why It's Commodity | Recommended Solutions |
|----------|-------------------|----------------------|
| Authentication / SSO | Standard protocols (OAuth2, OIDC, SAML), rapidly evolving security landscape | Auth0, Okta, Keycloak (self-hosted), AWS Cognito |
| Payment Processing | PCI compliance burden, fraud detection required, constantly evolving card networks | Stripe, Adyen, Braintree, Checkout.com |
| Email Delivery | Deliverability black magic, spam compliance, ISP relationships | SendGrid, Mailgun, AWS SES, Postmark |
| Monitoring / Observability | Massive data pipelines, storage, querying, visualization | Datadog, Grafana Cloud, New Relic, Honeycomb |
| Error Tracking | Smarter aggregation than you'll build, release tracking, source map support | Sentry, Bugsnag, Rollbar |
| Feature Flags | Complex targeting, percentage rollouts, kill switches | LaunchDarkly, Unleash (open-source) |
| Search | Inverted index, relevance ranking, faceting, geospatial — solved problems | Elasticsearch, Algolia, Meilisearch |
| CDN | Global edge network, DDoS protection, caching infrastructure | CloudFlare, Fastly, Akamai, CloudFront |
| Database (managed) | Operations, backups, failover, patching | RDS, Cloud SQL, MongoDB Atlas, PlanetScale |
| CI/CD | Build infrastructure, pipeline management, artifact storage | GitHub Actions, GitLab CI, CircleCI, Buildkite |
| Container Orchestration | Cluster management, networking, storage, scheduling | EKS, GKE, AKS (managed Kubernetes) |

### When Building Is a Mistake

**Case Study: The Custom Message Queue**

A team of 15 engineers at a mid-size company spent 8 months building an internal message queue because "RabbitMQ was too complex and Kafka was overkill." They built a system with:
- At-least-once delivery semantics (they claimed exactly-once, but had bugs)
- No dead letter queue (added 4 months later)
- JSON-only serialization (no schema, no versioning)
- In-memory storage (messages lost on restart)
- No clustering or failover

After 18 months, they migrated to a managed Kafka service. The custom queue had 14 production incidents, lost 3 batches of critical financial messages, and consumed ~40 person-months of total engineering time. The managed Kafka service cost $800/month.

**Why it was a mistake**: Message queuing is a deeply solved problem with mature, battle-tested solutions. The team built a worse version of RabbitMQ because of the "not invented here" syndrome. They confused "we understand our problem better than anyone else" with "we can build a better solution." The problem was standard; the solution needed to be standard too.

**Exception**: Building a custom message queue IS justified if your requirements are genuinely unique (e.g., you're building a trading platform that needs microsecond latency, or you need a custom persistence model that no existing queue supports, or you're a cloud provider for whom message queuing IS the product). The exception proves the rule.

### When Buying Is a Mistake

**Case Study: The Vendor Lock-in Trap**

A startup built their entire product on a "serverless" platform that abstracted away all infrastructure. Two years later:
- The platform raised prices 3x
- The platform discontinued the database product they depended on, giving 6 months' notice
- Key features they needed were "on the roadmap" for 18 months with no delivery
- One engineering team spent 12 months migrating off the platform, during which zero features shipped

**Why it was a mistake**: The team didn't evaluate the lock-in cost. They treated the buy decision as a technical convenience without considering: What happens if the vendor changes pricing? What happens if they discontinue the product? What happens if they have an outage? What happens if we outgrow their feature set?

**Lesson**: Buy from providers where the exit cost is understood and acceptable. Prefer open-source solutions or open standards that can be self-hosted if needed. Have a contingency plan: "If we need to migrate off Vendor X, it will take approximately Y months and cost Z engineer-months."

## 4. Runtime Behavior

### How Build vs Buy Decisions Manifest in Daily Work

A wrong build decision shows up as:
- "Can we delay the feature launch? The internal auth system isn't ready."
- "We need to hire another engineer for the internal platform team."
- "Known bug: the internal tooling does X wrong. Won't fix this quarter."
- Engineers spending time maintaining the parser/formatter/integration instead of building product features.

A wrong buy decision shows up as:
- "The vendor says that feature is on the roadmap for Q3... of next year."
- "We need to file another support ticket. Their SLA says 48-hour response."
- "They deprecated the API version we're on. Migration due in 3 months."
- "Our monthly bill is $47K. It was $2K when we started."

### Hybrid Models That Work

The most successful organizations use hybrid approaches:

```
Pattern: "Buy the commodity, build the differentiation on top"

Example — Authentication:
  BUY: Auth0 handles OAuth2/OIDC flows, MFA, social login, password reset,
       brute force protection, session management.
  BUILD: Your custom authorization service that maps Auth0 user IDs to
         your domain permissions, roles, and tenant scoping.
  
  Integration points:
  - Auth0 issues JWT → Your API validates JWT (standard)
  - Auth0 provides user_id → Your authorization service maps to permissions (custom)
  - Auth0 handles login UI → Your app redirects to Auth0 (standard)
```

```
Pattern: "Build on top of open-source"

Example — Monitoring:
  BUILD ON: Prometheus (open-source metrics), Grafana (open-source dashboards),
            OpenTelemetry (open-standard instrumentation)
  BUY: Grafana Cloud for the hosted/enterprise features if needed
  
  Advantage: You own the core (data, configuration, dashboards).
             The commercial layer adds convenience, not lock-in.
             You can self-host everything if needed.
```

```
Pattern: "Buy first, build if needed"

Example — Feature Flags:
  BUY: LaunchDarkly or Unleash for the first 12 months.
       Learn: What flag patterns do we actually use?
       What targeting capabilities do we genuinely need?
  THEN DECIDE: After 12 months of usage data, evaluate:
       - Do we use 20% or 80% of the vendor's features?
       - Is the vendor's roadmap aligned with our needs?
       - Would building a simplified version save money?
  Most common outcome: Keep buying. The vendor's solution is better
  than what you'd build, and you only use 30% of its capabilities.
```

## 5. Request Flow Diagrams

### Build vs Buy Decision Flow

```
Feature Request / Technology Need Identified
  │
  ├─ Is this a BUSINESS PROBLEM or a TECHNICAL NEED?
  │   └── Business: "We need to send notification emails"
  │   └── Technical: "We need a message queue"
  │
  ├─ Is this CORE or CONTEXT?  (Strategic assessment)
  │   ├── CORE → BUILD. No further analysis needed.
  │   │   But: Even for core, consider building ON TOP of
  │   │   buy decisions (e.g., core ML model on top of
  │   │   bought GPU infrastructure)
  │   └── CONTEXT → Continue analysis
  │
  ├─ Market maturity check
  │   ├── Multiple viable solutions exist? → Continue
  │   └── No mature solutions → BUILD or DELAY decision
  │
  ├─ TCO Analysis (3-year)
  │   ├── Build TCO: $X
  │   └── Buy TCO: $Y
  │   ├── X significantly < Y? → BUILD
  │   ├── X significantly > Y? → BUY
  │   └── X ≈ Y? → BUY (lower risk, faster time-to-market)
  │
  ├─ Risk Assessment
  │   ├── Buy: vendor lock-in risk, pricing risk, feature gap risk
  │   ├── Build: scope creep risk, key-person risk, maintenance burden
  │   └── Hybrid: integration complexity risk
  │
  ├─ Decision
  │   ├── BUILD → Allocate team, set milestones, define success criteria
  │   ├── BUILD LATER → Set trigger condition, document in backlog
  │   ├── BUY → Evaluate vendors, run PoC, negotiate, integrate
  │   ├── BUY NOW, BUILD LATER → Buy with exit plan
  │   └── DELEGATE → Offload to enabling team or platform team
  │
  └─ Write ADR → Communicate → Execute
```

## 6. Lifecycle Diagrams

### Conway's Law Lifecycle

```
Organization is formed
  │
  ├─ Teams organized by function
  │   (Frontend Team, Backend Team, DBA Team, Ops Team)
  │   ↓
  │   Architecture mirrors: Frontend codebase, Backend codebase,
  │   Database schema, Infrastructure repo
  │   ↓
  │   Problems emerge: Frontend and Backend teams can't agree on API specs.
  │   Coordination cost grows with team size.
  │   ↓
  ▼
Reorganization: Teams organized by business capability
  │   (Checkout Team, Search Team, Recommendations Team, Payments Team)
  │   Each team contains: Frontend, Backend, DB, Ops skills
  │   ↓
  │   Architecture mirrors: Checkout service, Search service,
  │   Recommendations service, Payments service
  │   ↓
  │   Problems emerge: Cross-cutting concerns (auth, logging, monitoring)
  │   are duplicated across teams. Inconsistent patterns.
  │   ↓
  ▼
Evolution: Add enabling teams
  │   (Platform Team, DevEx Team, Security Team, Data Platform Team)
  │   These teams build tools and infrastructure for stream-aligned teams.
  │   ↓
  │   Architecture mirrors: Platform services (auth, logging, deployment)
  │   support business capability services.
  │   ↓
  │   New problems: Platform team becomes bottleneck. Stream-aligned teams
  │   wait for platform team to build what they need.
  │   ↓
  ▼
Maturity: Platform as product, self-service
  │   Platform team treats stream-aligned teams as customers.
  │   Provides self-service capabilities, golden paths, paved roads.
  │   Stream-aligned teams only escalate when they need something unusual.
  │   ↓
  │   Architecture stabilizes: Clear contracts between platform and
  │   product services. Team autonomy maximized.
  │
  └─ This cycle repeats as the organization scales:
      5 engineers → 50 → 500 → 5000
```

## 7. Source Code Reading Guide

For this topic, "source code" means studying how successful organizations structure their teams and systems.

### Organizational Design Sources

1. **Shopify Engineering Blog** (`shopify.engineering`)
   - How they organize ~2000 engineers into product teams
   - Their journey from monolith to modular monolith (they famously did NOT go full microservices)
   - Platform engineering at scale

2. **Spotify Engineering Culture** (videos by Henrik Kniberg)
   - Squads, Tribes, Chapters, Guilds model
   - Note: Spotify has significantly evolved past this model; the videos show the 2014 version. Study what changed and why.

3. **Amazon's "Two-Pizza Teams" + API Mandate**
   - Bezos's 2002 mandate: all teams must communicate via APIs, all data must be accessible via APIs
   - This organizational decision directly produced AWS
   - Study: how an organizational rule (not a technology decision) shaped the world's largest cloud provider

4. **Conway's Law papers**
   - Melvin Conway's original 1968 paper: "How Do Committees Invent?"
   - Ruth Malan's "Conway's Law and its Misapplication"
   - The "Inverse Conway Maneuver" (Jonny LeRoy, ThoughtWorks)

5. **Team Topologies** (Matthew Skelton and Manuel Pais)
   - Four fundamental team types
   - Three interaction modes
   - The "fracture plane" concept

6. **Accelerate** (Nicole Forsgren, Jez Humble, Gene Kim)
   - Evidence-based organizational design
   - DORA metrics: deployment frequency, lead time, MTTR, change failure rate
   - What organizational structures correlate with high performance

## 8. Production Failure Scenarios

### Scenario 1: Organization Stays Layered, Tries to Build Microservices

**Symptom**: Company has separate Frontend, Backend, Database, and Operations teams. Leadership decides to move to microservices for faster feature delivery. After 12 months, feature delivery is SLOWER.

**Root cause**: The organization is structured by technical layer, but microservices require business capability-aligned teams. The Frontend team can't independently deliver a feature because they depend on the Backend team's API, which depends on the Database team's schema change, which depends on the Operations team's deployment pipeline. Every microservice crosses 4 teams.

**Resolution**: Reorganize teams by business capability FIRST. Then the architecture will naturally evolve toward bounded contexts. Organizational change precedes architecture change.

### Scenario 2: Platform Team Becomes Bottleneck

**Symptom**: The 8-person Platform Team builds shared infrastructure (CI/CD, logging, monitoring, deployment). Every product team depends on them. Platform Team's backlog is 6 months long. Product teams are blocked.

**Root cause**: Platform team is acting as a service team (taking requests) rather than an enabling team (building self-service). They are a single point of dependency.

**Resolution**: Treat platform as a product with internal customers. Prioritize self-service capabilities. A product team should be able to provision a new service, set up CI/CD, add monitoring, and deploy to production WITHOUT filing a ticket. If they file a ticket, the platform team has failed.

### Scenario 3: Build Decision Creates a Haunted House

**Symptom**: An internal tool (custom ORM, custom deployment system, custom testing framework) works but no one knows how. The original author left 2 years ago. Documentation is "I'll write it later" from 2019. Everyone is afraid to touch it.

**Root cause**: The build decision was made by one passionate engineer who moved on. No one else was trained, no documentation was written, no tests were written. The tool became a "haunted house" — everyone tiptoes around it, afraid of what might break.

**Resolution**: Flag haunted houses explicitly. If a built system has no owner and no documentation, treat it as a critical risk. Either: assign an owner and allocate time for documentation/tests, or plan a migration to a bought solution with vendor support.

## 9. Debugging Techniques

### Diagnosing Build vs Buy Failures

**Symptom: "We could have bought this for $500/month but we're spending $20K/month in engineering time maintaining our custom solution."**

Diagnosis: Calculate the fully-loaded cost of the engineers maintaining the custom solution. Compare to the license cost + integration cost of the best buy option. Present the numbers, not the emotions. "We are spending 0.5 engineers × $15K/mo = $7.5K/month to maintain a feature that would cost $500/month from VendorX. At this rate, we are losing $7K/month, or $84K/year, on this decision."

**Symptom: "Every vendor we evaluate is 'almost' right but has one critical gap."**

Diagnosis: The gap might be real or it might be a requirement that exists only because you're used to your custom solution. Distinguish:
- "We NEED this" → truly core feature
- "We've always done it this way" → adapt your process to the vendor's model
- "It would be nice to have" → accept the gap

**Symptom: "We have 7 different logging solutions across 12 teams."**

Diagnosis: This is an organizational failure, not a technology failure. When every team makes its own build vs buy decision for shared infrastructure, you get fragmentation. The platform team (or a cross-team working group) should standardize infrastructure decisions. Individual teams should not independently choose their logging/monitoring/CI/authentication solutions.

### Conway's Law Debugging

**Symptom: "The Accounts team and the Orders team are constantly blocking each other."**

Diagnosis: Look at the code dependencies between the Accounts module and Orders module. If they are tightly coupled, the team communication structure is correct (they DO need to coordinate) but the code structure is wrong (they should communicate via stable contracts, not shared code). If they are loosely coupled in code but still constantly meeting, the communication structure is wrong (they're over-communicating).

**Fix**: 
- Tight code coupling → Extract stable API contracts between the modules
- Unnecessary communication → Define clear team boundaries and interface contracts. The two teams should communicate through API specs, not daily standups.

## 10. Observability Considerations

### Metrics for Build vs Buy Health

| Metric | Healthy | Unhealthy | Action |
|--------|---------|-----------|--------|
| % engineering time on undifferentiated work | < 30% | > 50% | Audit: what are we building that we could buy? |
| Number of custom tools without an owner | 0 | > 0 | Assign owners or schedule sunset |
| Time to integrate a new SaaS tool | < 2 weeks | > 2 months | Evaluate procurement process |
| Vendor lock-in risk score | Low (can migrate in < 3 months) | High (migration > 12 months) | Build exit plan / contingency |
| Engineering satisfaction (build vs buy) | "I'm working on features that matter" | "I spend my day maintaining internal tools" | Review build decisions |

### Metrics for Organizational Health

| Metric | Healthy | Unhealthy | Action |
|--------|---------|-----------|--------|
| Team autonomy (can deploy independently) | Yes | No | Remove cross-team dependencies |
| Platform ticket queue depth | < 1 week | > 1 month | Build self-service, not ticket-based |
| Time for new engineer to deploy to production | < 1 day | > 2 weeks | Improve onboarding, developer experience |
| Cross-team communication overhead | Mostly async (docs, APIs, ADRs) | Mostly sync (meetings, Slack DMs) | Formalize interfaces between teams |

## 11. Performance Implications

### Organizational Performance

The structure of teams directly impacts system performance:

```
Functionally-Aligned Teams (by layer):
  Frontend Team → API waits (Backend Team hasn't built the endpoint)
  Backend Team → Schema waits (DBA Team hasn't approved migration)
  DBA Team → Deployment waits (Ops Team hasn't provisioned)
  
  Result: Feature delivery = sum of all wait times

Business-Capability-Aligned Teams:
  Checkout Team: Frontend + Backend + DB + Ops all in one team
    → Full-stack feature delivery within team
    → No cross-team dependencies for core work
  
  Result: Feature delivery = team's internal velocity
```

### Team Scaling Performance

```
5 engineers:   Single team. Shared codebase. Everyone knows everything.
               Communication: N*(N-1)/2 = 10 relationships. Manageable.

15 engineers:  2-3 teams. Module boundaries emerge. Some specialization.
               Communication: team-level. ~3-5 relationships between teams.

50 engineers:  5-8 teams. Formal API contracts between teams.
               Communication: team-level + architecture group for cross-cutting.

150 engineers: 15-20 teams. Platform team, security team, multiple product teams.
               Communication: tribal (team-level), platform (infra), guild (skills).

500 engineers: 50+ teams. Multiple platforms. Internal developer portal.
               Communication: formalized. Loose coupling, high cohesion at team level.
```

### The Inverse Conway Maneuver

If your desired architecture doesn't match your organizational structure:

```
DESIRED ARCHITECTURE:                    CURRENT ORGANIZATION:
┌──────────┐ ┌──────────┐ ┌──────────┐  ┌──────────┐ ┌──────────┐ ┌──────────┐
│ Checkout │ │  Search  │ │ Payments │  │Frontend  │ │ Backend  │ │    DB    │
│  Service │ │  Service │ │  Service │  │  Team    │ │   Team   │ │   Team   │
└──────────┘ └──────────┘ └──────────┘  └──────────┘ └──────────┘ └──────────┘

CONWAY PREDICTS: The code will mirror the 3 technical teams,
NOT the 3 business services.

INVERSE CONWAY MANEUVER:
1. Reorganize teams to match desired architecture
   ├── Team A: Checkout (contains frontend, backend, DB skills)
   ├── Team B: Search (contains frontend, backend, DB skills)
   └── Team C: Payments (contains frontend, backend, DB skills)

2. The codebase structure will naturally follow team boundaries
   ├── checkout/ └── Owner: Team A
   ├── search/   └── Owner: Team B
   └── payments/ └── Owner: Team C

3. Define interface contracts between teams
   ├── Team A provides: Checkout API (OpenAPI spec)
   ├── Team B provides: Search API (GraphQL schema)
   └── Team C provides: Payment webhook handlers
```

## 12. Architecture Implications

### Team Topologies — Four Fundamental Team Types

From *Team Topologies* by Skelton and Pais:

#### 1. Stream-Aligned Team
The "default" team type. Aligned to a single stream of work — a business domain, a product, a user journey.

```
Characteristics:
- Cross-functional (all skills needed to deliver independently)
- Owns a specific business capability end-to-end
- Deploys independently
- Long-lived (years, not project duration)
- 5-9 people (Amazon's "two-pizza team" rule)

Spring Boot context:
- Each stream-aligned team owns a Spring Boot service (or module)
- The team owns: controllers, services, repositories, infrastructure config
- Team boundary = Spring Boot application boundary (or Gradle module boundary)
```

#### 2. Enabling Team
Helps stream-aligned teams overcome obstacles. Provides coaching, expertise, tooling. Does NOT own services.

```
Characteristics:
- Composed of specialists (security, performance, DevOps, data)
- Temporary engagement with stream-aligned teams
- Goal: make the stream-aligned team self-sufficient, then move on
- NOT a permanent dependency

Spring Boot context:
- Security team: helps teams implement OAuth2/OIDC, reviews security configs
- Performance team: helps with JVM tuning, connection pool sizing, caching strategy
- DevOps team: helps with Docker, K8s manifests, CI/CD pipelines
```

#### 3. Complicated-Subsystem Team
Owns a subsystem that requires deep specialized expertise. Stream-aligned teams consume its output via APIs.

```
Characteristics:
- Deep expertise in a narrow domain (e.g., video transcoding, ML model serving)
- Maintains the complex subsystem
- Provides a simple API to stream-aligned teams

Spring Boot context:
- Search team: owns Elasticsearch cluster, provides search API
- ML Platform team: owns model serving infrastructure, provides inference API
- Payment Gateway team: owns payment processor integrations, provides payment API
```

#### 4. Platform Team
Builds and maintains the internal platform that stream-aligned teams build on. "Platform as a Product."

```
Characteristics:
- Treats stream-aligned teams as customers
- Provides self-service capabilities
- Curates "golden paths" and "paved roads"
- Reduces cognitive load on stream-aligned teams

Spring Boot context — what a platform team provides:
- Starter templates: generate new Spring Boot service with CI/CD, monitoring, auth
- Shared libraries: common logging, metrics, security, API patterns
- CI/CD pipeline templates: Jenkins shared library, GitHub Actions workflow
- Infrastructure provisioning: Terraform modules, K8s operators
- Observability: centralized logging, metrics, tracing, dashboards
```

### Three Team Interaction Modes

| Mode | Description | When to Use | Example |
|------|------------|-------------|---------|
| **Collaboration** | Two teams work closely together on a shared goal | During exploration, new system design, major migrations | Platform team + first stream-aligned team adopt new deployment system |
| **X-as-a-Service** | One team provides a service that another consumes | For stable, well-defined, commodity services | Platform team provides CI/CD-as-a-service; stream-aligned teams consume it |
| **Facilitating** | One team helps another team learn and improve | When a team needs temporary specialized expertise | Enabling team helps stream-aligned team adopt event-driven patterns |

### Communication Patterns as Architecture

```
SYNCHRONOUS COMMUNICATION:
  Team A makes API request to Team B's service → Waits for response
  └── Architecture: REST/gRPC endpoint exposed by Team B
  └── Coupling: Team A knows Team B's API. Team B must be available.
  └── When: Real-time needs, simple CRUD, request-response natural

ASYNCHRONOUS COMMUNICATION:
  Team A publishes domain event → Team B subscribes
  └── Architecture: Event bus (Kafka/RabbitMQ), Team B consumes independently
  └── Coupling: Team A knows the event schema. Team B doesn't need to be available.
  └── When: Loose coupling desired, eventual consistency acceptable, fan-out

CONTRACT-DRIVEN COMMUNICATION:
  Team A defines API contract (OpenAPI/Protobuf) → Team B implements compatible consumer
  └── Architecture: Contract-first development. Consumer-driven contract tests.
  └── Coupling: Both teams know the contract. Independent deployment.
  └── When: Multiple consumers, need for backward compatibility, formal interfaces
```

## 13. Team Ownership Implications

### Who Owns Build vs Buy Decisions

| Decision Type | Who Decides | Who Approves | Example |
|--------------|-------------|-------------|---------|
| Commodity SaaS (clearly buy) | Team lead | No approval needed | Choosing a monitoring tool from approved vendor list |
| Strategic build | Team lead with Staff engineer input | Engineering manager | Building a custom recommendation engine |
| Architecture-significant build | Staff engineer / Architect | Architecture review board | Building a new event sourcing framework |
| Buy with significant lock-in | Staff engineer / Architect | Architecture review board + CTO | Choosing a cloud-specific database (DynamoDB, Spanner) |
| Cross-team platform decisions | Platform team lead | Architecture review + affected teams | Choosing the message broker for all teams |

### Ownership Transfer Patterns

When a built system transitions from the building team to a maintenance team:

```
PHASE 1: Build Team owns everything (months 1-6)
  - Designs, builds, deploys, operates
  - Iterates rapidly based on user feedback
  
PHASE 2: Build Team operates, Maintenance Team shadows (months 4-8)
  - Maintenance team members join on-call rotation
  - Build team documents architecture, operational runbooks
  - Knowledge transfer sessions: 2 hours/week
  
PHASE 3: Co-ownership (months 7-10)
  - Both teams share on-call
  - Maintenance team takes over routine maintenance
  - Build team handles complex changes only
  
PHASE 4: Maintenance Team owns (month 10+)
  - Build team moves on to next project
  - Maintenance team owns operations, bug fixes, minor enhancements
  - Build team available for architecture questions (decreasing over time)
```

### The "You Build It, You Run It" Model

Amazon's principle: the team that builds a service is responsible for operating it in production. This creates:
- **Incentive alignment**: If you're on-call for what you build, you build it to be operable
- **Feedback loop**: Operations pain directly informs development priorities
- **No throw-over-the-wall**: DevOps is not a separate team you hand off to

**Spring Boot context**:
- Stream-aligned team is on-call for their Spring Boot services
- Platform team provides tools (monitoring, alerting, deployment) but does NOT operate product services
- If the Checkout service has a 3 AM outage, the Checkout team (not the Platform team) is paged

## 14. Interview Questions

### Question 1: "Your company is building a new SaaS product. The CTO wants to build an internal authorization/permission system because 'it's core to our product.' You believe buying is the right choice. Walk me through your argument."

**Staff-Level Answer**:

"First, I'd validate: is authorization truly CORE, or is it MISSION-CRITICAL CONTEXT? The distinction matters. If our product IS an authorization platform (like what Auth0 or Okta sell), then building is correct. But if we're building a project management tool that needs user permissions, authorization is context — necessary but not differentiating.

I'd present the argument in three parts:

**Part 1: TCO Analysis.** Building a production-grade authorization system requires:
- RBAC (Role-Based Access Control) with role hierarchies
- ABAC (Attribute-Based Access Control) for fine-grained rules
- Policy administration UI (for non-engineering admins)
- Audit logging of all permission changes
- Performance: permission checks must be sub-millisecond (they run on every request)
- Multi-tenancy: isolated permission sets per customer
- This is 6-12 months of work for 2 engineers → ~$300K-600K in salary alone

Versus buying: OpenFGA (open-source, CNCF), Permit.io, or Cerbos. Integration is 2-4 weeks. License cost is negligible compared to build cost.

**Part 2: The Authorization Market Is Solved.** Google's Zanzibar paper (2019) published their internal authorization system design. Multiple companies have built open-source implementations (OpenFGA, SpiceDB). The authorization problem — who can do what to which resource — has standard solutions. There's no competitive advantage in implementing RBAC from scratch in 2024.

**Part 3: What IS Actually Core.** If our product is a project management tool, the core differentiation is in the project management workflows, collaboration features, and integrations — not the permission system. The permission system needs to work, but it doesn't need to be custom. Our customers choose us for project management, not because we have the best permission model.

**Counter to the CTO's concern**: 'But what if the vendor doesn't support our specific permission model?' This is where an open-source solution (like OpenFGA) bridges the gap — we have the source code, we can extend it if genuinely needed, but we start with a production-grade foundation rather than building from scratch.

**My recommendation**: Start with OpenFGA (self-hosted, open-source, Zanzibar-compatible). If we genuinely outgrow it — and I'd set a clear metric for what 'outgrow' means — we can build a custom system. But by starting with an existing solution, we learn what authorization patterns work for our product before we commit to building."

---

### Question 2: "You join a 50-person engineering org with 3 stream-aligned teams and 1 platform team. The platform team has a 3-month backlog. Stream-aligned teams are duplicating infrastructure work because they can't wait. Diagnose the problem and propose a solution."

**Staff-Level Answer**:

"The symptoms describe a classic platform team bottleneck. The platform team has become a blocker, not an enabler. The diagnosis:

1. **The platform team is operating as a service team, not a platform team.** They're taking tickets and building custom solutions for each stream-aligned team. This doesn't scale.

2. **The platform is not self-service.** Stream-aligned teams should be able to do 80% of their infrastructure work without filing a ticket. If they can't, the platform's abstraction is wrong.

3. **Cognitive load on stream-aligned teams is being pushed to building their own infrastructure** because the official solution is too slow.

**Proposed solution — three parallel tracks:**

**Track 1: Immediate Relief (Weeks 1-4)**
- Pause the platform team's bottom 50% of backlog items. They're low-priority nice-to-haves.
- Embed one platform engineer into each stream-aligned team for 4 weeks. This addresses the duplication problem immediately — the embedded engineer brings platform knowledge to the team.
- The embedded engineers report back to the platform team: 'Here's what the teams are building themselves. Here's what they actually need from us.'

**Track 2: Platform as Product (Weeks 4-12)**
- The platform team defines their 'customers' as the stream-aligned teams.
- They interview each stream-aligned team: 'What infrastructure work do you do most often? What's the most painful part?'
- Based on input, prioritize: the top 3 infrastructure tasks should be automated to self-service.
- Example: 'Provisioning a new service with CI/CD, monitoring, and database' → becomes a templated command or web UI, not a ticket.

**Track 3: Golden Paths and Paved Roads (Ongoing)**
- Define 'golden paths': the recommended, fully-supported way to do common tasks.
- Example: The golden path for a new REST API is: use the Spring Boot starter template, use the shared Kafka library for events, use the shared observability library for metrics. Everything is pre-configured.
- 'Paved roads': if you follow the golden path, everything works. If you need something unusual, you can go off the paved road — but you're responsible for the maintenance.
- The platform team supports the paved road. Teams that go off-road are on their own.

**The goal**: Within 6 months, stream-aligned teams should be able to do 80% of their infrastructure work without filing a ticket. The platform team's work shifts from fulfilling tickets to building self-service capabilities."

---

### Question 3: "You're advising a startup that's growing from 15 to 50 engineers. They currently have a single Spring Boot monolith, organized by layer (controller/service/repository). They want to move to microservices. What do you tell them?"

**Staff-Level Answer**:

"I tell them: do not move to microservices yet. Move to a modular monolith first. Here's why:

**At 15 engineers, the monolith is the correct architecture.** The communication overhead of microservices (API versioning, contract testing, distributed tracing, eventual consistency handling) exceeds the benefit. At 15 engineers, you can all understand the monolith.

**At 50 engineers, the monolith might start to strain** — but the problem is not the deployment unit (monolith vs microservices). The problem is the internal organization (no module boundaries). A well-structured modular monolith can support 50 engineers easily.

**My recommendation — three phases:**

**Phase 1 (Now, at 15 engineers): Modularize the Monolith**
- Reorganize code into domain packages: `com.example.checkout/`, `com.example.search/`, `com.example.accounts/`
- Enforce module boundaries with ArchUnit tests (no checkout code importing search internals)
- Define clear API interfaces between modules (Java interfaces, not REST)
- Introduce domain events for cross-module communication (Spring ApplicationEvents)
- Goal: Each module can be understood independently. Developer says 'I work on checkout' not 'I work on services.'

**Phase 2 (At 30-40 engineers): Extract Platform**
- Create a platform module that owns cross-cutting concerns (auth, logging, monitoring, CI/CD templates)
- Each domain module uses platform via well-defined APIs
- Shared infrastructure standardized across teams

**Phase 3 (At 50+ engineers): Evaluate Extraction**
- By now, each module has clear boundaries, stable APIs, and a team that owns it
- If a module genuinely needs independent scaling (e.g., search is 10x the load of everything else) → extract it
- If a module needs independent deployment frequency (e.g., checkout deploys daily, accounts deploys weekly) → extract it
- If a module needs a different technology stack (e.g., search service needs Elasticsearch, not PostgreSQL) → extract it
- Otherwise: keep it in the monolith. A well-modularized monolith with 5 modules and 50 engineers is a HEALTHY architecture

**The evidence**: Shopify has thousands of engineers and is famously a modular monolith (not microservices). They extract services only when there's a clear scaling or organizational need.

**The counter to the 'everyone is doing microservices' argument**: Every company that succeeded with microservices — Netflix, Amazon, Uber — was ALREADY a successful company when they adopted microservices. They had the revenue, the engineering capacity, and the organizational need. A 15-person startup adopting microservices is solving problems they don't have yet at a cost they can't afford."

## 15. Hands-On Exercises

### Exercise 1: Build vs Buy Analysis for Your Current Project

Identify one thing your team currently builds that could be bought:
1. Calculate the 3-year TCO of your current custom solution (engineer-hours × cost/hour for development, maintenance, operations)
2. Research 3 commercial or open-source alternatives
3. Calculate the 3-year TCO for each buy option (license cost + integration cost + maintenance)
4. Write a 1-page recommendation: keep building or switch to buying
5. If the recommendation is to switch: estimate the migration cost and timeline

### Exercise 2: Map Your Organization to Team Topologies

Draw your organization's team structure:
1. Which teams are stream-aligned? Which are enabling? Which are complicated-subsystem? Which are platform?
2. For each team, identify: Are they structured correctly for their function?
3. Identify: Where are the bottlenecks? Which teams block other teams?
4. Draw the communication patterns between teams. Are they synchronous (meetings, tickets) or asynchronous (APIs, documentation)?
5. Propose one organizational change that would reduce cross-team coordination overhead.

### Exercise 3: Write an ADR for a Build vs Buy Decision

Choose a real decision your team faces (or recently faced):
1. Follow the build vs buy decision framework (6-step analysis)
2. Document in ADR format
3. Include TCO analysis with realistic numbers
4. Include risk assessment for both build and buy options
5. Include a re-evaluation trigger and timeline

### Exercise 4: Conway's Law Audit

For your current project:
1. Draw the codebase's module/package dependencies
2. Draw the team structure and reporting lines
3. Overlay them: Do the code dependencies mirror the team communication structure?
4. Identify mismatches: Where does the code structure diverge from team boundaries?
5. Is the mismatch causing friction? (e.g., two teams constantly modifying the same code)
6. Propose: Should you change the code structure, the team structure, or both?

## 16. Advanced Challenges

### Challenge 1: Build an Internal Developer Platform (IDP) Proposal

Design a proposal for an internal developer platform for a 100-engineer organization:
1. Define the platform's scope: what services does it provide? (CI/CD, observability, service provisioning, secrets management, etc.)
2. Define the 'golden paths': the 5 most common workflows the platform must make self-service
3. Define the platform team's composition: how many engineers, what skills?
4. Define metrics for the platform: how do you know it's successful?
5. Define the platform team's interaction model with stream-aligned teams
6. Write the proposal as an ADR

### Challenge 2: Design an Organizational Structure for a Fictional Company

**Scenario**: "FinPay" is building a payment processing platform. They have:
- 80 engineers (growing to 200 in 2 years)
- Products: Payment Gateway API, Merchant Dashboard, Fraud Detection, Reconciliation Engine, Partner Integrations
- Need 99.99% uptime SLA
- PCI DSS Level 1 compliance required

Design:
1. Team structure (stream-aligned, enabling, complicated-subsystem, platform)
2. Which teams own which services/modules
3. Communication patterns between teams (synchronous vs asynchronous)
4. Build vs buy decisions for non-core components
5. How the organization evolves from 80 → 200 engineers

### Challenge 3: Conduct a Build vs Buy Retrospective

Pick a build decision your organization made more than 2 years ago:
1. Interview the original decision-makers (if possible): what was their reasoning?
2. Calculate the actual TCO vs the estimated TCO
3. Identify what went right and what went wrong
4. Write a retrospective ADR (status: Superseded or Retained)
5. Extract lessons learned: what would you do differently?
6. Apply those lessons to a CURRENT build vs buy decision

### Challenge 4: Implement a Self-Service Platform MVP

Build the minimum viable version of a self-service platform for your team:
1. Create a starter template that generates a new Spring Boot service with:
   - Standard project structure
   - CI/CD pipeline (GitHub Actions / Jenkins)
   - Dockerfile and K8s manifests
   - Shared observability (logging, metrics, tracing)
   - Standard security configuration
2. Document the 'golden path' for creating a new service
3. Measure: how long does it take for a new team member to create and deploy a "hello world" service?
4. Iterate: reduce the time by 50%

### Challenge 5: Facilitate an Inverse Conway Maneuver

If your organization has a mismatch between team structure and desired architecture:
1. Document the desired architecture (target state)
2. Document the current team structure
3. Design a phased reorganization plan that moves from current to target
4. Include: timeline, transition plan, communication plan, risk mitigation
5. Present the plan to leadership as a business case (not a technical argument):
   - "This reorganization will reduce our feature delivery time by 40%"
   - Not: "This reorganization aligns with Conway's Law"
6. Write the plan as an ADR with status: Proposed
