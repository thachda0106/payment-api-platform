# Phase 03 — Risk Analysis & Threat Modeling

## MoMo-like Payment API Platform

> **Document Status**: ✅ Approved v3.1 — Final  
> **Last Updated**: 2026-03-23  
> **Audience**: Engineering Leadership, Security Team, Architecture Review Board  
> **Input**: Phase 02 — Requirements & SLOs (v3.0, Approved)

---

## 1. Goal

Identify and prioritize technical, business, and security risks BEFORE architecture decisions are locked. Build a threat model using STRIDE. Map compliance requirements to controls. Perform FMEA on critical payment flows.

---

## 2. Risk Register

### 2.1 Risk Scoring Methodology

| Dimension | 1 (Very Low) | 2 (Low) | 3 (Medium) | 4 (High) | 5 (Critical) |
|-----------|-------------|---------|------------|----------|--------------|
| **Probability** | < 1%/year | 1–5%/year | 5–20%/year | 20–50%/year | > 50%/year |
| **Impact** | < $1K loss, no user impact | < $10K, < 100 users | < $100K, < 10K users | < $1M, service degradation | > $1M, full outage or data breach |

**Risk Priority = Probability × Impact**

| Priority Score | Level | Response |
|---------------|-------|----------|
| 1–4 | Low | Accept or monitor |
| 5–9 | Medium | Mitigate within quarter |
| 10–15 | High | Mitigate before launch |
| 16–25 | Critical (P0) | Mitigate immediately, block launch if unresolved |

### 2.2 Technical Risks

| ID | Risk | Probability | Impact | Priority | Mitigation | Owner | Status |
|----|------|------------|--------|----------|-----------|-------|--------|
| TR-01 | **Database single point of failure** — wallet_db failure causes complete payment outage | 2 | 5 | **10 (High)** | Synchronous replication + hot standby + automated failover. RTO < 5 min. | SRE | Mitigated by design |
| TR-02 | **Ledger inconsistency** — sum(debits) ≠ sum(credits) due to partial transaction failure | 2 | 5 | **10 (High)** | Same-DB transaction for wallet+ledger (co-located). Reconciliation job every 15 min. Automated alert on any imbalance. | Backend Lead | Mitigated by design |
| TR-03 | **Kafka message loss** — events lost between outbox and consumer | 2 | 4 | **8 (Med)** | Outbox pattern (DB transaction), replication factor=3, acks=all, inbox deduplication, DLQ with replay. | Platform | Mitigated by design |
| TR-04 | **Scalability cliff at 500 RPS** — wallet_db vertical scaling limit reached | 3 | 4 | **12 (High)** | Connection pooling (PgBouncer), read replicas, partitioning strategy ready. Vertical scale for Year 1, partition plan for Year 2. | SRE | Plan ready |
| TR-05 | **Redis cluster failure** — rate limiting and caching unavailable | 2 | 3 | **6 (Med)** | Redis Sentinel auto-failover, graceful degradation (bypass cache, use DB), circuit breaker on Redis calls. | Platform | Mitigated by design |
| TR-06 | **Connection pool exhaustion** — database connections depleted under load | 3 | 4 | **12 (High)** | PgBouncer (transaction pooling), connection limits per service, pool utilization alerts at 80%. | Backend Lead | Plan ready |
| TR-07 | **N+1 query patterns** — ORM generates inefficient queries at scale | 3 | 3 | **9 (Med)** | Code review checklist, query logging in staging, p99 latency alerts, SQL query analysis in CI. | Backend Lead | Ongoing |
| TR-08 | **Event schema evolution breaks consumers** — backward-incompatible schema changes | 3 | 3 | **9 (Med)** | Schema registry, backward-compatible-only rule, versioned events, schema validation in CI. | Platform | Plan ready |
| TR-09 | **Cascading failure** — one service failure takes down dependent services | 3 | 4 | **12 (High)** | Circuit breakers (per-call), bulkhead isolation, timeout budgets, async-first communication, fallback responses. | Platform | Mitigated by design |
| TR-10 | **Hot partition** — single Kafka partition overwhelmed by high-volume user/merchant | 2 | 3 | **6 (Med)** | Partition key = aggregate_id (distributed), monitor partition lag, support re-keying if needed. | Platform | Monitoring |

### 2.3 Business Risks

| ID | Risk | Probability | Impact | Priority | Mitigation | Owner | Status |
|----|------|------------|--------|----------|-----------|-------|--------|
| BR-01 | **Regulatory non-compliance** — SBV e-wallet license requirements not met | 2 | 5 | **10 (High)** | Compliance control mapping (Section 4), KYC tiers, transaction limits, reporting capabilities. Engage legal counsel early. | Compliance | In progress |
| BR-02 | **Fraud loss exceeds threshold** — real-time fraud detection misses patterns | 3 | 4 | **12 (High)** | Multi-layer fraud defense: velocity checks, device fingerprint, geo-anomaly, ML model (Year 2). False positive rate < 5%. Review rules monthly. | Risk Team | Plan ready |
| BR-03 | **Bank partnership dependency** — primary bank partner changes terms or goes down | 3 | 4 | **12 (High)** | Multi-bank strategy: minimum 2 bank partners per transaction type. Bank abstraction layer. Automated failover between banks. | Business Dev | In progress |
| BR-04 | **Data breach / PII exposure** — customer PII leaked via exploit, insider, or misconfiguration | 2 | 5 | **10 (High)** | Encryption at-rest + in-transit, PCI zone isolation, field-level encryption for PII, access audit logging, data masking, penetration testing. Breach notification SLA 72h. | Security | Mitigated by design |
| BR-05 | **Settlement reconciliation failures** — mismatch between ledger, wallet, and bank | 3 | 4 | **12 (High)** | Three-way reconciliation, daily automated matching, exception queue, manual resolution SLA < 24h, financial alerts. | Finance | Plan ready |
| BR-06 | **Vendor lock-in** — over-dependence on single cloud/payment provider | 2 | 3 | **6 (Med)** | Infrastructure as Code (Terraform), containerized services (cloud-portable), abstraction layers for external services. | Architecture | Accepted |
| BR-07 | **Team capacity** — insufficient engineering capacity for 17 services | 3 | 3 | **9 (Med)** | Tier-based build order (critical services first), shared platform libraries, service template scaffolding, phased rollout. | Eng Manager | Plan ready |
| BR-08 | **Cost explosion** — infrastructure costs spike due to traffic surge, misconfiguration, or resource leak | 3 | 3 | **9 (Med)** | FinOps guardrails (Phase 02 §16.6), budget alerts at 110%/150%, auto-scaling limits (max instances), reserved instances, monthly cost review. | SRE + FinOps | Plan ready |
| BR-09 | **Insider attack** — malicious employee modifies balances, exfiltrates data, or sabotages systems | 1 | 5 | **5 (Med)** | Dual approval for financial ops, RBAC + MFA, audit logging all admin actions, background checks, access reviews quarterly, break-glass review. | Security | Mitigated by design |
| BR-10 | **Key/credential compromise** — encryption keys, JWT signing keys, or bank API credentials compromised | 1 | 5 | **5 (Med)** | KMS-managed keys, automated rotation, short-lived certs (24h), break-glass rotation procedure, secret scanning in CI, access audit logging. | Security | Mitigated by design |

### 2.4 Operational Risks

| ID | Risk | Probability | Impact | Priority | Mitigation | Owner | Status |
|----|------|------------|--------|----------|-----------|-------|--------|
| OR-01 | **Deployment causes outage** — bad deployment to critical service | 3 | 4 | **12 (High)** | Canary deployment for Tier 0, blue/green for Tier 1, auto-rollback on SLI degradation, feature flags. | SRE | Mitigated by design |
| OR-02 | **Incident response too slow** — P0 incident not resolved within RTO | 2 | 5 | **10 (High)** | On-call rotation, runbooks per service, incident response process (Phase 02 §15), quarterly DR drills. | SRE | Plan ready |
| OR-03 | **Secret/credential leak** — credentials exposed in logs, code, or breach | 2 | 5 | **10 (High)** | AWS Secrets Manager, never in env vars, secret scanning in CI, log PII redaction, 90-day rotation, break-glass procedure. | Security | Mitigated by design |
| OR-04 | **Monitoring blind spots** — critical failure not detected by alerting | 3 | 4 | **12 (High)** | RED + USE metrics, SLO burn-rate alerts, synthetic probes, reconciliation alerts, ledger balance check alert. Quarterly alert review. | SRE | Plan ready |
| OR-05 | **Backup restore failure** — backup exists but cannot be restored | 2 | 5 | **10 (High)** | Weekly automated restore tests, monthly PITR test for wallet_db, quarterly full DR drill, daily checksum verification. | SRE | Plan ready |
| OR-06 | **Certificate expiration** — mTLS or TLS cert expires causing outage | 2 | 4 | **8 (Med)** | Short-lived certs (24h) via cert-manager auto-rotation, ACM managed certs for external TLS, expiration monitoring alert at 7d. | Platform | Mitigated by design |
| OR-07 | **Operational mistake** — engineer runs wrong command, deletes data, or misconfigures production | 3 | 4 | **12 (High)** | Dual approval for destructive ops, soft delete by default, PITR backups, IaC (no manual changes), staging environment for testing, runbook enforcement. | SRE | Plan ready |
| OR-08 | **Region-wide outage** — entire AWS region unavailable | 1 | 5 | **5 (Med)** | DR region with async replicas, IaC-ready failover, Route 53 health checks, quarterly DR drill. RTO < 15 min. | SRE | Plan ready |

### 2.5 Risk Heat Map

```
Impact ↑
  5 │  BR-09    TR-01,TR-02   OR-02,OR-03
    │  BR-10    BR-04,OR-05   OR-08
  4 │  TR-10    TR-03,TR-05   TR-04,TR-06    BR-02,BR-03
    │           OR-06         TR-09,OR-01    BR-05,OR-04
    │                         OR-07
  3 │           BR-06         TR-07,TR-08    BR-07,BR-08
    │                         
  2 │                         
    │
  1 │
    └──────────────────────────────────────────→ Probability
       1          2              3              4          5
```

**Total risks: 31** (10 technical, 10 business, 8 operational + 3 security in register)

---

## 3. STRIDE Threat Model

### 3.1 STRIDE Categories

| Category | Description | Primary Concern |
|----------|-------------|----------------|
| **S** — Spoofing | Pretending to be another user or service | Authentication bypass |
| **T** — Tampering | Modifying data in transit or at rest | Data integrity |
| **R** — Repudiation | Denying having performed an action | Audit trail gaps |
| **I** — Information Disclosure | Exposing data to unauthorized parties | Data leakage |
| **D** — Denial of Service | Making services unavailable | Availability |
| **E** — Elevation of Privilege | Gaining unauthorized access levels | Authorization bypass |

### 3.2 STRIDE Analysis per Service Boundary

#### API Gateway

| Threat | Category | Attack Vector | Severity | Mitigation |
|--------|----------|--------------|----------|-----------|
| JWT token forgery | **S** | Attacker crafts JWT with stolen/weak signing key | Critical | RS256 signing (asymmetric), KMS-managed keys, short expiry (15 min access token) |
| Request parameter tampering | **T** | Modify amount, recipient in transit | Critical | TLS 1.3 + request signing for merchant API, input validation (Zod schemas) |
| DDoS attack | **D** | Volumetric or application-layer flood | High | AWS Shield + WAF, per-IP rate limiting (1000 req/min), geo-blocking |
| API key theft | **S** | Stolen merchant API key used to create payments | Critical | API key hashing (SHA-256), IP allowlisting for merchants, key rotation |
| Header injection | **T** | Inject malicious headers (X-Forwarded-For) | Medium | Strict header validation, trusted proxy configuration |

#### Wallet Service

| Threat | Category | Attack Vector | Severity | Mitigation |
|--------|----------|--------------|----------|-----------|
| Balance manipulation | **T** | Direct DB access or SQL injection | Critical | DB-per-service isolation, parameterized queries (ORM), serializable isolation, RLS |
| Unauthorized balance query | **I** | Access another user's balance | High | JWT user context enforcement, row-level filtering, audit logging |
| Race condition on balance | **T** | Concurrent debit exceeds available balance | Critical | Serializable transactions, `SELECT FOR UPDATE`, application-level idempotency |
| Insufficient fund bypass | **E** | Exploit timing to spend more than balance | Critical | Atomic check-and-debit in single transaction, pessimistic locking |

#### Payment Service

| Threat | Category | Attack Vector | Severity | Mitigation |
|--------|----------|--------------|----------|-----------|
| Double-spend attack | **T** | Submit same payment twice before first completes | Critical | Idempotency key enforcement, unique constraint on payment_id, state machine transitions |
| Payment amount manipulation | **T** | Modify amount between fraud check and execution | Critical | Amount locked at creation, verified at each step, immutable payment record |
| Transaction repudiation | **R** | User denies making a transaction | High | PIN authentication, device fingerprint, audit trail, transaction receipts |
| Payment flow bypass | **E** | Skip fraud check or limit check | Critical | Orchestrator enforces step order, each step validates prerequisites |

#### Ledger Service

| Threat | Category | Attack Vector | Severity | Mitigation |
|--------|----------|--------------|----------|-----------|
| Ledger entry tampering | **T** | Modify historical journal entries | Critical | Append-only table (no UPDATE/DELETE), checksums on entries, reconciliation verification |
| Imbalanced entry creation | **T** | Create debit without corresponding credit | Critical | DB constraint: `sum(debits) = sum(credits)` per journal, application-level validation |
| Unauthorized ledger access | **I** | Read financial data without authorization | High | Service-to-service mTLS + service JWT, no direct DB access, audit logging |

#### Fraud Service

| Threat | Category | Attack Vector | Severity | Mitigation |
|--------|----------|--------------|----------|-----------|
| Rule engine bypass | **E** | Craft transaction to avoid all rules | High | Multiple rule layers (velocity + amount + device + geo), ML model (Year 2), continuous rule tuning |
| Velocity counter manipulation | **T** | Reset Redis velocity counters | High | Redis ACL (no FLUSHALL), counter persistence to DB, tamper-evident logging |
| False positive flooding | **D** | Trigger mass fraud alerts to overwhelm review team | Medium | Alert aggregation, auto-resolution for known patterns, queue prioritization |

#### Bank Integration Service

| Threat | Category | Attack Vector | Severity | Mitigation |
|--------|----------|--------------|----------|-----------|
| Man-in-the-middle on bank API | **I/T** | Intercept/modify bank API calls | Critical | mTLS to bank endpoints, TLS 1.3, certificate pinning, IP allowlisting |
| Callback spoofing | **S** | Fake bank callback to confirm fraudulent top-up | Critical | HMAC signature verification on callbacks, source IP verification, idempotent callback processing |
| Bank credential compromise | **I** | Bank API credentials leaked | Critical | AWS Secrets Manager, never in env vars, 90-day rotation, access audit, break-glass rotation |

#### Notification Service

| Threat | Category | Attack Vector | Severity | Mitigation |
|--------|----------|--------------|----------|-----------|
| SMS OTP interception | **I** | SIM swap or SS7 attack intercepts OTP | High | OTP expiry (5 min), max 3 attempts, rate limit (5/hour), consider TOTP/push auth for high-value |
| Template injection | **T** | Inject malicious content in notification templates | Medium | Template parameterization (no raw interpolation), output encoding |
| Notification spam | **D** | Trigger mass notifications to drain SMS credits | Medium | Per-user rate limit, budget alerts on SMS spend, deduplication |

#### Admin Dashboard / Internal APIs

| Threat | Category | Attack Vector | Severity | Mitigation |
|--------|----------|--------------|----------|-----------|
| Privilege escalation | **E** | Admin modifies own role or accesses restricted data | Critical | RBAC enforcement at API layer, dual approval for role changes, audit logging |
| Insider threat | **T/I** | Malicious admin modifies balances or exports PII | Critical | Dual approval for financial operations, data masking, all actions audit-logged, break-glass review |
| Session hijacking | **S** | Steal admin session token | High | 30-min session timeout, IP binding, MFA required, secure cookie flags |

### 3.3 Threat Summary by Severity

| Severity | Count | Top Threats |
|----------|-------|------------|
| **Critical** | 14 | Balance manipulation, double-spend, ledger tampering, JWT forgery, bank credential compromise |
| **High** | 9 | Race conditions, repudiation, OTP interception, unauthorized access |
| **Medium** | 5 | Header injection, template injection, notification spam, false positive flooding |

---

## 4. Compliance Control Mapping

### 4.1 State Bank of Vietnam (SBV) — E-Wallet Regulations

| Requirement | Control | Implementation | Evidence |
|------------|---------|---------------|----------|
| KYC verification for all users | Tiered KYC (Tier 0–2) | KYC Service: ID verification, liveness check, document storage | KYC completion reports, verification logs |
| Transaction limits per KYC tier | Configurable limit engine | Limit Service: per-tier daily/monthly/per-txn limits | Limit configuration, enforcement logs |
| Record keeping (10 years) | Immutable transaction records | Ledger (append-only), Transaction Service, S3 archival with lifecycle | Retention policy, archival verification |
| Suspicious activity reporting | SAR filing capability | Fraud Service alerts → Compliance review → SAR generation | SAR filing records, alert logs |
| Anti-money laundering | AML screening | Sanctions list checking, velocity monitoring, threshold alerts | Screening logs, alert resolution records |
| Capital requirements | Daily reconciliation | Reconciliation Service: bank ↔ ledger ↔ wallet three-way match | Reconciliation reports, exception logs |
| Real-time settlement capability | Settlement Service | EOD batch settlement + real-time for P2P (same-system) | Settlement reports, timing logs |

### 4.2 PCI-DSS v4.0 (if card data handled)

| Requirement | Control | Implementation | Evidence |
|------------|---------|---------------|----------|
| **1. Network segmentation** | PCI zone isolation | Dedicated PCI subnet (10.0.30.0), strict security groups | Network diagrams, firewall rules |
| **2. Protect stored data** | Encryption at-rest | AES-256 TDE for PCI zone databases, field-level encryption | Encryption configuration, key inventory |
| **3. Protect data in transit** | TLS 1.3 everywhere | mTLS internal, TLS 1.3 external, certificate management | TLS configuration, cert inventory |
| **4. Vulnerability management** | Regular scanning | Quarterly ASV scans, dependency scanning in CI | Scan reports, remediation status |
| **5. Access control** | RBAC + MFA | Admin roles, MFA for all PCI zone access, least privilege | RBAC matrix, MFA enrollment |
| **6. Monitoring & testing** | Logging + pen testing | Security audit logging (7yr retention), annual pen test | Audit logs, pen test reports |
| **7. Information security policy** | Documented policies | Security architecture doc (Phase 02 §17), incident response (Phase 02 §15) | Policy documents, review dates |

### 4.3 PDPA (Vietnam Personal Data Protection)

| Requirement | Control | Implementation | Evidence |
|------------|---------|---------------|----------|
| Consent for data processing | Explicit consent at registration | Registration flow: consent checkbox + terms acceptance | Consent records, timestamp |
| Data minimization | Collect only necessary data | Defined PII fields per service, no unnecessary collection | Data inventory, schema review |
| Right to access | Data export API | Admin API to generate user data export (JSON) | Export API, SLA < 24h |
| Right to erasure | Soft delete + hard delete pipeline | Soft delete → 30-day hold check → hard delete (after regulatory retention) | Deletion logs, retention evidence |
| Breach notification (72h) | Incident response process | P0 security incident process → notify regulator within 72h | Incident runbook, notification template |
| Cross-border data transfer | Data residency | All data stored in Vietnam region (ap-southeast-1) | Infrastructure configuration |

### 4.4 SOC 2 Type II

| Trust Principle | Control | Implementation | Evidence |
|----------------|---------|---------------|----------|
| **Security** | Access control, encryption, network segmentation | RBAC, mTLS, PCI zone, WAF, secrets management | Security config, audit logs |
| **Availability** | SLOs, redundancy, failover | 99.99% Tier 0 SLOs, multi-AZ, auto-failover, backup strategy | SLO dashboards, DR test reports |
| **Confidentiality** | Data classification, encryption | 5-level classification, AES-256, field-level PII encryption | Data inventory, encryption config |
| **Processing Integrity** | Reconciliation, validation | Three-way reconciliation, ledger balance checks, idempotency | Reconciliation reports, balance checks |
| **Privacy** | PDPA compliance | Consent, data minimization, rights implementation | Privacy controls, consent records |

### 4.5 Compliance Readiness Summary

| Framework | Readiness | Gaps | Priority |
|-----------|----------|------|----------|
| **SBV E-Wallet** | 85% | SAR filing automation, capital requirement reporting | P0 — must complete before launch |
| **PCI-DSS v4.0** | 70% | ASV scan setup, pen test scheduling, formal policy docs | P1 — required if card data handled |
| **PDPA** | 80% | Data export API, formal breach notification process | P1 — before launch |
| **SOC 2 Type II** | 60% | Formal audit engagement, evidence collection, policy documentation | P2 — within 6 months post-launch |

---

## 5. Third-Party Risk Matrix

| Vendor | Service | Criticality | SLA | Fallback | Vendor Lock-in Risk | Mitigation |
|--------|---------|------------|-----|----------|--------------------|-----------| 
| **NAPAS** | Interbank transfers | Critical | 99.9%, < 5s | Direct bank API | High (monopoly) | Build direct bank integrations as backup, queue-and-retry on failure |
| **Partner Banks** (VCB, VTB, BIDV) | Fund transfers, settlements | Critical | 99.5%, < 15s | Multi-bank strategy (≥ 2 banks per txn type) | Medium | Bank abstraction layer, automated failover, contract diversification |
| **eKYC Provider** | Identity verification | High | 99.5%, < 10s | Manual review queue | Medium | Evaluate 2nd provider, manual review fallback, cache verified identities |
| **SMS Gateway** (primary) | OTP delivery, notifications | High | 99.9%, < 5s | Secondary SMS provider | Low (commodity) | Dual-provider setup, automatic failover, push notification as backup |
| **FCM / APNs** | Push notifications | Medium | 99.9%, < 3s | In-app notification | Low | In-app fallback, retry with backoff, not used for security-critical flows |
| **AWS** | Infrastructure (compute, DB, S3, KMS) | Critical | 99.99% | Multi-region failover | High | IaC (Terraform), container portability, avoid proprietary services where possible |
| **Card Network (via acquirer)** | Card payments | Medium | 99.95%, < 3s | "Card unavailable" message | Medium | Acquirer abstraction, multiple processor support ready |
| **Utility Providers** | Bill payment APIs | Low | 99%, < 10s | "Provider unavailable" | Low | Provider aggregator, per-provider circuit breaker |

### 5.1 Vendor Concentration Risk

| Risk | Assessment | Mitigation |
|------|-----------|-----------|
| **Single cloud provider (AWS)** | High dependency, but best-in-class for SEA | Terraform IaC (cloud-portable), avoid proprietary services (use PostgreSQL not Aurora Serverless), container-based deployment |
| **Single interbank network (NAPAS)** | No alternative for interbank in Vietnam | Direct bank API integrations as backup, SLA monitoring, escalation process |
| **Single eKYC provider** | Risk if provider increases prices or degrades | Evaluate 2nd provider in Year 1, cache verified KYC results (valid for 5 years) |

---

## 6. Technical Risk Assessment

### 6.1 Scalability Cliff Analysis

| Component | Current Design Capacity | Cliff Point | Symptom | Mitigation Timeline |
|-----------|------------------------|------------|---------|---------------------|
| **wallet_db** (PostgreSQL) | ~500 RPS write, ~2000 RPS read | ~1000 RPS write | Connection exhaustion, p99 > 1s | Year 1: PgBouncer + read replicas. Year 2: horizontal partitioning by user_id range |
| **Kafka cluster** (3 brokers) | ~725 events/sec peak | ~3000 events/sec | Broker disk I/O saturation | Year 1: sufficient. Year 2: add brokers, partition expansion |
| **Redis cluster** (3 nodes, 15 GB) | ~50K ops/sec | ~200K ops/sec | Memory limit, eviction starts | Year 1: sufficient. Year 2: cluster expansion, secondary Redis for non-critical |
| **API Gateway** (3 instances) | ~3,150 RPS (design) | ~5,000 RPS | CPU saturation, connection limits | HPA auto-scaling, pre-provisioned for peak |
| **OpenSearch** (2 nodes) | ~50 GB index | ~200 GB | Query latency > 1s | Year 1: sufficient. Year 2: add data nodes, index lifecycle management |
| **Single region** | ~3,150 RPS | Regional outage | Full platform down | Year 1: DR region (cold). Year 2: active-passive multi-region |

### 6.2 Single Points of Failure (SPOF) Analysis

| SPOF | Risk Level | Current State | Mitigation |
|------|-----------|--------------|-----------|
| **wallet_db primary** | Critical | Single primary, sync standby | Automated failover (< 5 min), monitored replication lag |
| **Kafka controller** | High | KRaft controller (single) | Multi-controller KRaft (3 controllers), ISR-based leader election |
| **API Gateway** | High | 3 instances behind ALB | ALB health checks, auto-scaling, cross-AZ deployment |
| **Redis primary** | Medium | Sentinel-managed failover | Auto-failover (< 10s), graceful degradation if Redis unavailable |
| **NAT Gateway** | Medium | Single NAT per AZ | Multi-AZ NAT, redundant egress paths |
| **DNS (Route 53)** | Low | AWS-managed, 100% SLA | Already highly available, health-check based failover configured |
| **KMS** | Low | AWS-managed, multi-AZ | Regional KMS, key replication configured |

### 6.3 Data Loss Scenarios

| Scenario | RPO Impact | Detection | Recovery | Prevention |
|----------|-----------|-----------|----------|-----------|
| **wallet_db primary crash** | 0 (sync replication) | Health check failure (< 30s) | Promote sync standby (< 5 min) | Sync replication, automated failover |
| **Accidental data deletion (admin)** | Minutes (PITR available) | Audit log alert | PITR restore to before deletion (30–60 min) | Dual approval for destructive ops, soft delete default |
| **Ransomware/full compromise** | < 1 min (cross-region backup) | Security monitoring, file integrity | Restore from immutable cross-region backup (1–4h) | Immutable backups, network segmentation, access control |
| **Kafka topic data loss** | 0 (replication factor=3) | ISR count alert | Auto leader election (< 30s) | RF=3, min.insync.replicas=2, acks=all |
| **S3 data corruption** | < 15 min (CRR lag) | S3 event notification, checksum | Restore from cross-region replica or versioned copy | Versioning enabled, CRR, checksum verification |

---

## 7. Detailed Scenario Analysis

### 7.1 Fraud Scenarios

#### Scenario F1: Account Takeover via SIM Swap

```
Timeline:
T+0:      Attacker performs SIM swap at telecom carrier
T+1min:   Attacker requests OTP login → intercepted via swapped SIM
T+2min:   Attacker logs in, changes PIN
T+3min:   Attacker drains wallet via P2P transfers to mule accounts
T+5min:   Real user notices no phone service
T+10min:  User contacts support
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Telecom-side SIM swap (outside our control) |
| **Detection** | Device fingerprint change alert, new device + immediate large transfers, velocity check (sum > daily limit in < 5 min) |
| **Blast Radius** | Single user account, potential total wallet balance loss |
| **Mitigation** | Device binding (new device requires additional verification), push auth as OTP backup, velocity limits (₫10M/day even for Tier 2), large transfer cooling period (30 min for > ₫5M) |
| **Recovery** | Freeze account, reverse transfers if mule accounts still have funds, file SAR, reimburse user per policy |

#### Scenario F2: Merchant Collusion (Fake QR Payments)

```
Timeline:
T+0:      Fraudulent merchant creates QR codes
T+0-72h:  Accomplice users make payments, merchant receives settlement
T+24h:    Settlement batch includes fraudulent transactions
T+48h:    Accomplice users file disputes claiming unauthorized
T+72h:    Merchant withdraws settlement funds
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Collusion between merchant and users |
| **Detection** | Pattern analysis: same users paying same merchant repeatedly, refund ratio > 10%, dispute ratio > 5% per merchant, new merchant + high volume |
| **Blast Radius** | Platform financial loss (settlement already paid, user refunds from platform) |
| **Mitigation** | Merchant settlement hold period (T+3 for new merchants, T+1 for established), dispute rate monitoring, merchant risk scoring, settlement cap for new merchants |
| **Recovery** | Freeze merchant account, claw back pending settlements, block accomplice accounts, file SAR |

#### Scenario F3: Synthetic Identity Fraud

```
Timeline:
T+0:      Attacker creates account with synthetic identity (real ID + fake details)
T+0-30d:  "Seasons" account with small legitimate transactions
T+30d:    Upgrades KYC tier with forged documents
T+31d:    Receives multiple top-ups from compromised accounts
T+32d:    Transfers funds to external bank via withdrawal
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Weak KYC verification accepting forged documents |
| **Detection** | eKYC liveness detection, ID document verification (OCR + database check), cross-reference with known fraud patterns, account behavior anomaly |
| **Blast Radius** | Money laundering risk, regulatory penalties |
| **Mitigation** | Tier 2 KYC with manual review, enhanced liveness check, withdrawal cooling period for newly upgraded accounts, sanctions list screening |
| **Recovery** | Freeze account, report to authorities, enhance KYC verification rules |

### 7.2 Double Payment Scenarios

#### Scenario DP1: Client-Side Double Submission

```
Timeline:
T+0ms:     User taps "Pay" button
T+50ms:    Network latency — no response yet
T+500ms:   User taps "Pay" again (impatient)
T+550ms:   First request reaches Payment Service → starts processing
T+600ms:   Second request reaches Payment Service → starts processing
T+1000ms:  Both requests attempt to debit wallet
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | User double-tap, client retry, or network timeout + retry |
| **Detection** | Idempotency key check at API Gateway (reject duplicate within 5 min window) |
| **Blast Radius** | Double debit from sender wallet |
| **Mitigation** | Client-side: disable button after tap, show loading state. Server-side: idempotency key (hash of sender+receiver+amount+timestamp), unique constraint on payment_id, `SELECT FOR UPDATE` on wallet balance |
| **Recovery** | If double debit occurs: automated reconciliation detects within 15 min, compensating credit issued |

#### Scenario DP2: Outbox Relay Duplicate

```
Timeline:
T+0:       Payment completes, outbox entry written
T+1s:      Relay reads outbox, publishes to Kafka → success
T+1.5s:    Relay crashes before marking outbox entry as published
T+5s:      Relay restarts, re-reads same outbox entry
T+5.5s:    Relay publishes same event AGAIN to Kafka
T+6s:      Consumer processes duplicate event
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Outbox relay crash after publish but before marking as sent |
| **Detection** | Consumer-side inbox deduplication check (event_id unique constraint) |
| **Blast Radius** | Duplicate downstream side-effects (double notification, double settlement entry) |
| **Mitigation** | Inbox pattern: consumer stores processed event_id in inbox table, rejects duplicates. All consumers must be idempotent. Event_id = deterministic hash (not random UUID) |
| **Recovery** | If duplicate processed: reconciliation detects, compensating entry created |

### 7.3 Ledger Inconsistency Scenarios

#### Scenario LI1: Partial Transaction Due to Application Crash

```
Timeline:
T+0ms:     Transaction starts: BEGIN
T+5ms:     Debit wallet: UPDATE wallets SET balance = balance - 50000
T+10ms:    Credit ledger debit entry: INSERT INTO journal_entries (type=DEBIT)
T+15ms:    Application process killed (OOM, deployment, etc.)
T+15ms:    Credit ledger credit entry: *** NEVER EXECUTED ***
T+16ms:    PostgreSQL: connection lost → auto-ROLLBACK entire transaction
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Application crash mid-transaction |
| **Detection** | PostgreSQL automatically rolls back uncommitted transactions |
| **Blast Radius** | **NONE** — database ACID guarantees prevent partial state |
| **Mitigation** | Wallet + Ledger in same database (co-located), single DB transaction wraps ALL operations. If any step fails → entire transaction rolls back atomically |
| **Recovery** | No recovery needed — transaction was never committed |

#### Scenario LI2: sum(debits) ≠ sum(credits) Detected by Reconciliation

```
Timeline:
T+0:       Reconciliation job runs (every 15 min)
T+5s:      Query: SELECT SUM(debit) - SUM(credit) FROM journal_entries WHERE ...
T+5.1s:    Result: difference = ₫50,000 ← MISMATCH
T+5.2s:    P0 ALERT fired → PagerDuty + SMS to on-call
T+5.5s:    All new payments HALTED via feature flag
T+10min:   Root cause: bug in refund service created debit without credit
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Software bug in a service that writes to ledger without proper double-entry |
| **Detection** | Reconciliation job (every 15 min), P0 alert, `sum(debits) = sum(credits)` constraint |
| **Blast Radius** | Financial integrity compromised, regulatory risk |
| **Mitigation** | DB constraint enforcing balanced entries per journal, reconciliation job, application-level validation (every ledger write MUST have equal debit + credit), code review checklist for ledger operations |
| **Recovery** | 1) Halt new transactions (feature flag). 2) Identify root cause from journal audit trail. 3) Create compensating journal entry. 4) Fix bug. 5) Resume transactions. 6) Post-mortem |

### 7.4 Event Duplication Scenarios

#### Scenario ED1: Kafka Consumer Rebalance During Processing

```
Timeline:
T+0s:      Consumer A reads message from partition 0
T+1s:      Consumer A starts processing (DB write)
T+3s:      Consumer A becomes slow (GC pause)
T+5s:      Kafka triggers rebalance (session.timeout.ms exceeded)
T+5.1s:    Partition 0 reassigned to Consumer B
T+5.2s:    Consumer B reads SAME message, starts processing
T+6s:      Consumer A completes processing, tries to commit → FAIL (no longer owns partition)
T+7s:      Consumer B completes processing → commit → SUCCESS
Result:    Message processed TWICE (once by A, once by B)
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Consumer slowness + Kafka rebalance |
| **Detection** | Inbox deduplication rejects second processing attempt |
| **Blast Radius** | Duplicate side-effects if consumer is not idempotent |
| **Mitigation** | Inbox table with unique event_id constraint. Increase `session.timeout.ms` (30s). Reduce `max.poll.records`. Consumer processing must be idempotent (same input → same output, no additional side-effects) |
| **Recovery** | Reconciliation detects, compensating entries if needed |

### 7.5 Event Loss Scenarios

#### Scenario EL1: Kafka Broker Failure with Under-Replicated Partitions

```
Timeline:
T+0:       Broker 2 has disk failure
T+0:       Partitions on Broker 2 with ISR=1 (only on Broker 2) → DATA LOST
T+0.1s:    Partitions with ISR≥2 → leader election, no data loss
T+5s:      Alert: under-replicated partitions detected
T+30s:     New leader elected for ISR≥2 partitions
Result:    Events on ISR=1 partitions are LOST
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Broker hardware failure + under-replicated partitions |
| **Detection** | ISR count alert (< min.insync.replicas), under-replicated partition alert |
| **Blast Radius** | Events on affected partitions lost — downstream services never receive them |
| **Mitigation** | `replication.factor=3`, `min.insync.replicas=2`, `acks=all` (producer waits for all ISR replicas). Monitor ISR count continuously. Alert if any partition has ISR < 2 |
| **Recovery** | For lost events: re-read from source of truth (outbox table) and re-publish. Outbox table is persistent in PostgreSQL — events are never lost at source |

#### Scenario EL2: Outbox Table Not Polled (Relay Down)

```
Timeline:
T+0:       Transactions completing, outbox entries accumulating
T+5min:    Outbox relay (CDC/polling) crashes silently
T+5-60min: Outbox entries NOT being published to Kafka
T+60min:   Alert: Kafka consumer lag = 0 but new transactions exist (gap detected)
T+65min:   Relay restarted, begins publishing backlog
T+70min:   All backlogged events published, consumers process
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Relay process crash or deployment issue |
| **Detection** | Health check on relay process, outbox table "unpublished count" metric, gap detection (new txns but no events) |
| **Blast Radius** | Downstream effects delayed (notifications, settlement aggregation, transaction history update) but NO DATA LOSS |
| **Mitigation** | Relay health check, outbox age alert (no entry should be > 5 min old unpublished), relay auto-restart, redundant relay instances |
| **Recovery** | Restart relay → automatic catchup from outbox table. All events published in order. Consumers process backlog |

### 7.6 Region Outage Scenario

#### Scenario RO1: Full AWS Region Failure

```
Timeline:
T+0:          AWS ap-southeast-1 experiences region-wide outage
T+0:          All services, databases, Kafka unavailable
T+30s:        Route 53 health checks fail (3 consecutive failures)
T+60s:        Route 53 failover: DNS points to DR region
T+2min:       DR region: promote DB replicas to primary
T+5min:       DR region: start services from container images (IaC)
T+10min:      Kafka: MirrorMaker catches up (Year 2) or cold start
T+12min:      Smoke tests pass in DR region
T+15min:      Traffic flowing to DR region (RTO achieved)
T+15min-??:   Data gap: async replication lag = up to 1 min for Tier 0, 5 min for others
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | AWS region-wide infrastructure failure (extremely rare, ~1/decade) |
| **Detection** | Route 53 health checks, external synthetic monitoring, PagerDuty alert |
| **Blast Radius** | Complete platform outage until DR failover completes (15 min RTO) |
| **Data loss** | Tier 0 (wallet/ledger): < 1 min (sync replication cross-AZ, async cross-region). Tier 1-3: < 5 min |
| **Mitigation** | DR region with pre-provisioned infra (IaC), async DB replicas, automated failover runbook |
| **Recovery** | 1) DNS failover (automatic). 2) Promote DB replicas. 3) Start services. 4) Verify data integrity. 5) Reconcile data gap. 6) When primary region recovers: failback with data sync |
| **Post-incident** | Full reconciliation of data gap period. Notify affected users of potential delays |

### 7.7 Backup Restore Scenario

#### Scenario BR1: Corrupted wallet_db Requires PITR

```
Timeline:
T+0:          Bug deployed: UPDATE wallets SET balance = 0 WHERE ... (wrong WHERE clause)
T+30s:        Hundreds of wallets zeroed out
T+1min:       Alert: mass balance changes detected, P0 fired
T+2min:       Feature flag: disable all payments
T+5min:       Identify corruption timestamp from audit logs
T+10min:      Start PITR restore to T-1min (before corruption)
T+25min:      PITR restore complete to staging DB
T+30min:      Extract corrected wallet records from restored DB
T+45min:      Apply corrected records to production DB (surgical fix)
T+50min:      Verify all balances match audit trail
T+55min:      Re-enable payments via feature flag
T+60min:      Post-mortem initiated
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Bad deployment, SQL migration error, or data corruption bug |
| **Detection** | Mass balance change alert, reconciliation alert, user complaints |
| **RPO** | 0 (PITR with continuous WAL archiving) |
| **RTO** | ~55 minutes (from detection to full recovery) |
| **Mitigation** | Continuous WAL archiving, daily full backups, dual approval for any SQL running against production, automated migration testing in staging |
| **Key lesson** | Never run raw SQL in production without dual approval. All schema changes via migration framework |

### 7.8 Cost Explosion Scenario

#### Scenario CE1: Auto-Scaling Runaway

```
Timeline:
T+0:          DDoS attack or viral event causes 10× traffic spike
T+1min:       HPA triggers: scale from 3 → 30 instances (payment-service)
T+2min:       Other services scale similarly (cascade)
T+5min:       Total instances: 35 → 200+ (all services scaling)
T+10min:      Database connections exhausted (200 instances × 10 conn = 2000)
T+15min:      Database goes down from connection exhaustion
T+30min:      Services failing, still scaling up (scaling doesn't help)
T+60min:      Daily compute cost: $500/day → $5,000/day (10×)
T+24h:        Monthly projection: $12,500 → $125,000+
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | DDoS, viral event, or misbehaving client generating excessive traffic |
| **Detection** | Budget alert at 150% daily threshold, instance count alert (> 3× normal), connection pool exhaustion alert |
| **Blast Radius** | Massive cost overrun, potential database failure from connection exhaustion |
| **Mitigation** | HPA max replicas cap (e.g., 10 per service), PgBouncer connection pooling, rate limiting at WAF/API Gateway, budget alerts, auto-scaling cooldown periods |
| **Recovery** | 1) Identify traffic source (DDoS vs legitimate). 2) If DDoS: WAF block. 3) Scale down instances manually. 4) Review auto-scaling policies. 5) File cost anomaly report |

#### Scenario CE2: Storage Leak

```
Timeline:
T+0:          New feature deployed: logs request/response bodies (DEBUG level in prod)
T+1-7d:       Log volume: 10 GB/day → 500 GB/day
T+7d:         Elasticsearch storage: 50 GB → 3.5 TB, auto-scales storage
T+14d:        S3 log archive: grows by 7 TB
T+30d:        Monthly storage bill: $95 → $3,000+
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Misconfigured log level, missing log rotation, or unbounded data retention |
| **Detection** | Disk usage alerts (> 80%), storage cost alerts, log volume anomaly detection |
| **Mitigation** | Log level enforced per environment (no DEBUG in prod), log volume per-service quotas, S3 lifecycle policies, log sampling for high-volume endpoints |
| **Recovery** | Fix log level, delete excessive logs, restore lifecycle policies |

### 7.9 Insider Attack Scenario

#### Scenario IA1: Rogue Admin Modifies Balances

```
Timeline:
T+0:          Malicious FINANCE_ADMIN logs into admin dashboard (MFA verified)
T+1min:       Attempts to modify wallet balance directly
T+1min:       BLOCKED — balance modification requires SUPER_ADMIN + dual approval
T+2min:       Attempts to create fake refund to transfer funds to personal account
T+2min:       Refund created (within FINANCE_ADMIN scope: amount ≤ ₫500,000)
T+3min:       Multiple fake refunds created (10 × ₫500,000 = ₫5,000,000)
T+5min:       Alert: FINANCE_ADMIN issuing > 5 refunds in 10 min (anomaly)
T+6min:       Account frozen by security team
T+10min:      Audit log review reveals all fraudulent refunds
T+30min:      All fake refunds reversed, account disabled
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Malicious or compromised admin account |
| **Detection** | Anomaly detection on admin operations: volume spike, unusual patterns, refund amount exceeding daily threshold per admin |
| **Blast Radius** | Financial loss up to admin's permission scope (₫500K per refund for FINANCE_ADMIN) |
| **Mitigation** | RBAC with least privilege, daily refund cap per admin (₫5M), anomaly alerts, dual approval for amounts > ₫1M, mandatory MFA, quarterly access reviews |
| **Recovery** | Freeze admin account, reverse fraudulent transactions, audit all admin's recent actions, report to authorities |

### 7.10 Key Compromise Scenario

#### Scenario KC1: JWT Signing Key Leaked

```
Timeline:
T+0:          JWT RSA private key accidentally committed to Git repo
T+1h:         Automated secret scanner detects key in commit (GitHub secret scanning)
T+1h:         Security alert fired
T+1h+5min:    Break-glass key rotation initiated
T+1h+10min:   New key version created in KMS
T+1h+15min:   All services restarted with new signing key
T+1h+15min:   Old key disabled — all existing JWTs invalidated
T+1h+15min:   All users forced to re-authenticate
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Key committed to code, leaked via logs, or insider exfiltration |
| **Detection** | Secret scanning in CI/CD (pre-commit hook + repo scanning), access audit logging |
| **Blast Radius** | Attacker can forge JWTs for any user → full account access for all users |
| **Mitigation** | KMS-managed keys (key material never leaves KMS), asymmetric signing (private key never in app memory), pre-commit hooks blocking secrets, key rotation procedure |
| **Recovery** | 1) Emergency key rotation (< 15 min). 2) Invalidate all existing tokens (force re-auth). 3) Review access logs for the compromise window. 4) Identify and fix leak source. 5) Post-mortem |

#### Scenario KC2: Bank API Credentials Compromised

```
Timeline:
T+0:          Attacker gains access to bank API credentials
T+10min:      Attacker initiates fraudulent withdrawals via bank API
T+15min:      Anomaly: withdrawals without matching platform transactions
T+20min:      Alert: bank API call volume spike from unknown source IP
T+25min:      Emergency credential rotation via break-glass procedure
T+30min:      Bank notified, fraudulent transactions flagged for reversal
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Credential theft via exploitation, insider, or third-party breach |
| **Detection** | Bank API call monitoring (unexpected source IPs), withdrawal/platform reconciliation mismatch |
| **Blast Radius** | Direct financial loss from fraudulent bank transactions |
| **Mitigation** | Credentials in AWS Secrets Manager only, IP allowlisting for bank API calls, anomaly detection on bank API usage, 90-day credential rotation |
| **Recovery** | 1) Emergency credential rotation. 2) Notify bank partner. 3) Freeze affected transactions. 4) Full audit of compromise window. 5) Update access controls |

### 7.11 PII Leak Scenario

#### Scenario PL1: Database Query Exposing PII via API

```
Timeline:
T+0:          New API endpoint deployed with bug: returns full user profile including national_id
T+1-7d:       Endpoint serving unmasked PII to API consumers
T+7d:         Security review catches unmasked PII in API response
T+7d+1h:      Hotfix deployed: mask PII fields
T+7d+2h:      Assess blast radius: how many requests served unmasked data?
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Software bug bypassing PII masking, missing field-level encryption check |
| **Detection** | API response schema validation in CI (check for PII fields), security review, penetration testing |
| **Blast Radius** | PII exposure for all users queried via the buggy endpoint |
| **Mitigation** | API response schema validation (block responses containing raw PII), field-level encryption (PII encrypted at rest, decrypted only with explicit scope), data masking library enforced via middleware, code review checklist for PII handling |
| **Recovery** | 1) Hotfix to mask/remove PII. 2) Identify all affected API calls from access logs. 3) Notify affected users. 4) Report to regulator within 72h (PDPA). 5) Post-mortem. 6) Add automated PII detection test |

#### Scenario PL2: Log File Contains PII

```
Timeline:
T+0:          Developer adds request body logging for debugging
T+0-30d:      Every payment request body logged (includes phone, amount, user details)
T+30d:        PII audit catches phone numbers in Elasticsearch logs
T+30d+1h:     Logging config updated to redact PII fields
T+30d+2h:     Historical logs with PII queued for deletion
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Insufficient PII redaction in logging middleware |
| **Detection** | Automated PII scanner on log output, quarterly PII audit, log review |
| **Blast Radius** | PII accessible to anyone with log access (SRE, platform team) |
| **Mitigation** | Mandatory PII redaction middleware (runs on ALL log output), allowlist approach (only explicitly permitted fields logged), automated PII detection in log pipeline, PII detection CI test |
| **Recovery** | 1) Update logging config. 2) Delete affected log data. 3) Review log access during exposure period. 4) Add PII detection to CI pipeline |

### 7.12 Operational Mistake Scenarios

#### Scenario OM1: Wrong Database Migration in Production

```
Timeline:
T+0:          Engineer runs migration: ALTER TABLE wallets DROP COLUMN balance
T+0:          ERROR: this was meant for staging, not production
T+0.1s:       All wallet balance queries fail immediately
T+1s:         Cascade: payment service errors → 100% error rate
T+5s:         Alert: error rate > 50% → P0 fired
T+30s:        Engineer realizes mistake
T+1min:       Feature flag: disable all payments
T+5min:       PITR restore initiated for wallet_db
T+20min:      Balance column restored from PITR
T+25min:      Re-enable payments
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Running migration against wrong environment (staging vs production) |
| **Detection** | Immediate error spike, health check failures, P0 alert |
| **Blast Radius** | Complete payment outage (RTO: ~25 min) |
| **Mitigation** | 1) Migrations ONLY via CI/CD pipeline (never manual). 2) Environment connection string verification step. 3) Migration dry-run in staging first. 4) Expand-contract pattern (never destructive in single step). 5) Dual approval for production migrations. 6) Read-only credentials for humans |
| **Recovery** | PITR restore for destructive changes, feature flags to halt traffic |

#### Scenario OM2: Accidental Production Data Deletion

```
Timeline:
T+0:          Support engineer runs DELETE FROM users WHERE status = 'FROZEN'
T+0:          Intended: delete test data. Actual: deletes real frozen users
T+1s:         1,200 user records deleted (soft delete disabled on this table)
T+5min:       Alert: user count anomaly (1,200 fewer users)
T+10min:      Identify cause from audit log
T+15min:      PITR restore to T-1min
T+25min:      Extract deleted user records from restore
T+35min:      Re-insert user records into production
T+40min:      Verify all 1,200 users restored
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Engineer running ad-hoc SQL against production database |
| **Detection** | Audit log alert on DELETE operations, row count anomaly |
| **Blast Radius** | 1,200 users unable to access their accounts |
| **Mitigation** | 1) Soft delete by default on ALL tables. 2) No direct SQL access to production (read-only replicas for queries). 3) All data modifications via API/admin dashboard with dual approval. 4) Staging-only for ad-hoc queries. 5) Row-count verification before DELETE/UPDATE |
| **Recovery** | PITR restore → extract → re-insert. All operations logged |

#### Scenario OM3: Feature Flag Misconfiguration

```
Timeline:
T+0:          Engineer toggles `enable-payment-service-v2` to ON in production
T+0:          Intended: enable for beta users. Actual: enabled for ALL users
T+1min:       V2 payment flow has unresolved bug → 30% of payments fail
T+3min:       Error rate alert fires
T+5min:       Engineer realizes flag was set globally, not per-segment
T+5.5min:     Flag toggled back to OFF
T+6min:       Payments resume with V1 flow
```

| Aspect | Detail |
|--------|--------|
| **Trigger** | Feature flag scope misconfiguration (global vs segment) |
| **Detection** | Error rate spike alert, SLO burn-rate alert |
| **Blast Radius** | All users affected by buggy V2 flow (~5 min exposure) |
| **Mitigation** | Feature flag UI with confirmation dialogs, flag change audit log, gradual rollout (never 0% → 100%), change approval for production flags, instant rollback capability |
| **Recovery** | Toggle flag off (instant, < 1 min). Review impacted transactions, issue compensations if needed |

---

## 8. FMEA (Failure Mode and Effects Analysis) — Top 5 Critical Flows

### 8.1 P2P Transfer Flow

| Step | Failure Mode | Effect | Severity (1-10) | Probability (1-10) | Detection (1-10) | RPN | Control |
|------|-------------|--------|-----------------|--------------------|----|-----|---------|
| 1. PIN verification | PIN service timeout | Transfer blocked | 6 | 2 | 2 | 24 | Timeout 2s, retry 1x, fail-safe (block) |
| 2. Fraud check | Fraud service unavailable | Transfer blocked (fail-closed) | 7 | 2 | 2 | 28 | Circuit breaker, 50ms timeout, fail-closed policy |
| 3. Limit check | Limit service unavailable | Transfer blocked | 6 | 2 | 2 | 24 | Redis cache fallback, circuit breaker, fail-closed |
| 4. Balance check | Stale balance (cache) | Over-debit risk | 9 | 2 | 3 | 54 | No caching for writes — always read from DB with `SELECT FOR UPDATE` |
| 5. Debit sender | Transaction failure mid-debit | Partial state (debited but not credited) | 10 | 1 | 2 | 20 | Same-DB transaction (wallet + ledger co-located), atomic operation |
| 6. Credit receiver | DB write failure | Sender debited, receiver not credited | 10 | 1 | 1 | 10 | Same transaction as debit (atomic), rollback on any failure |
| 7. Record transaction | Outbox write failure | Transaction completed but not recorded | 7 | 1 | 3 | 21 | Same transaction includes outbox entry, retry on consumer side |
| 8. Send notifications | Notification service down | Users not notified | 3 | 3 | 2 | 18 | Async via Kafka, retry 3x, in-app fallback, not blocking |

**Highest RPN**: Step 4 (stale balance → over-debit) — mitigated by eliminating cache for write-path balance checks.

### 8.2 QR Payment Flow

| Step | Failure Mode | Effect | Severity | Probability | Detection | RPN | Control |
|------|-------------|--------|----------|-------------|-----------|-----|---------|
| 1. QR decode | Invalid/expired QR | Payment rejected | 3 | 3 | 1 | 9 | Client-side QR validation, clear error message |
| 2. Merchant lookup | Merchant service unavailable | Payment blocked | 7 | 2 | 2 | 28 | Cache active merchant data in Redis (TTL 5 min), circuit breaker |
| 3. Fraud check | False positive blocks legitimate payment | User frustrated, lost sale | 6 | 3 | 4 | 72 | Tuned thresholds, merchant whitelist, < 5% false positive target |
| 4. Debit user + credit merchant_pending | Partial transaction | Debited but not credited to merchant | 10 | 1 | 1 | 10 | Atomic DB transaction, compensating entry on failure |
| 5. Platform fee calculation | Fee miscalculation | Revenue loss or overcharge | 8 | 1 | 3 | 24 | Fee rules in config (not code), reconciliation catches drift |
| 6. Merchant notification | Webhook delivery failure | Merchant doesn't know payment received | 5 | 2 | 2 | 20 | Retry 5x (exponential backoff), dashboard shows payment, manual query |

**Highest RPN**: Step 3 (false positive) — ongoing rule tuning required with monthly review cycle.

### 8.3 Wallet Top-Up (Bank → Wallet)

| Step | Failure Mode | Effect | Severity | Probability | Detection | RPN | Control |
|------|-------------|--------|----------|-------------|-----------|-----|---------|
| 1. Initiate bank debit | Bank API timeout | Top-up stuck in PENDING | 5 | 3 | 2 | 30 | 30s timeout, mark PENDING, reconcile via bank statement |
| 2. Bank callback | Callback lost/delayed | Wallet not credited | 7 | 2 | 3 | 42 | Polling reconciliation every 15 min, bank statement match daily |
| 3. Callback spoofing | Fake callback credits wallet | Fraudulent top-up (free money) | 10 | 1 | 2 | 20 | HMAC signature verification, source IP check, amount cross-check |
| 4. Credit wallet | DB failure during credit | Bank debited but wallet not credited | 9 | 1 | 2 | 18 | Idempotent callback processing, compensating entry, reconciliation |
| 5. Duplicate callback | Same callback processed twice | Double credit to wallet | 10 | 2 | 2 | 40 | Inbox deduplication (unique callback_id), idempotency check |

**Highest RPN**: Step 2 (callback lost) — mitigated by active polling reconciliation plus daily bank statement matching.

### 8.4 Settlement (EOD Merchant Payout)

| Step | Failure Mode | Effect | Severity | Probability | Detection | RPN | Control |
|------|-------------|--------|----------|-------------|-----------|-----|---------|
| 1. Aggregate transactions | Missing transactions in aggregation | Under-settlement to merchant | 8 | 2 | 3 | 48 | Three-way reconciliation, count verification, completeness check |
| 2. Fee calculation | Incorrect fee deduction | Revenue loss or merchant dispute | 7 | 1 | 4 | 28 | Fee rules versioned in config, reconciliation validates fees |
| 3. Generate settlement report | Report generation failure | Settlement delayed | 5 | 2 | 2 | 20 | Retry with backoff, alert on delay > 1h, manual trigger capability |
| 4. Initiate bank transfer | Bank rejects transfer | Merchant not paid on time | 7 | 2 | 2 | 28 | Retry 3x, fallback bank, alert to ops, manual intervention if needed |
| 5. Settlement amount mismatch | Rounding errors in aggregation | Small discrepancy per merchant | 4 | 3 | 3 | 36 | Banker's rounding, reconciliation with tolerance < ₫1, alert on any diff |

**Highest RPN**: Step 1 (missing transactions) — critical to run completeness checks before settlement execution.

### 8.5 Authentication Flow (Login + PIN)

| Step | Failure Mode | Effect | Severity | Probability | Detection | RPN | Control |
|------|-------------|--------|----------|-------------|-----------|-----|---------|
| 1. OTP delivery | SMS gateway failure | User cannot log in | 6 | 2 | 2 | 24 | Dual SMS provider, push notification fallback, retry 3x |
| 2. OTP verification | Brute force attack | Account compromise | 10 | 2 | 2 | 40 | Max 3 attempts per OTP, 5 OTPs/hour, account lock after 10 failures |
| 3. JWT issuance | KMS unavailable | No tokens issued, all auth blocked | 9 | 1 | 1 | 9 | Multi-AZ KMS, cached signing key (short window), health check |
| 4. PIN verification | Timing attack on PIN check | PIN extracted via response timing | 8 | 1 | 5 | 40 | Constant-time comparison (Argon2id), rate limiting, no timing leak |
| 5. Session management | Token theft | Unauthorized access | 9 | 2 | 3 | 54 | Short-lived tokens (15 min), refresh token rotation, device binding |

**Highest RPN**: Steps 4-5 (PIN timing attack, token theft) — mitigated by constant-time comparison and short token lifetimes.

### 8.6 FMEA Summary — Highest Risk Items

| Rank | Flow | Step | RPN | Risk | Key Control |
|------|------|------|-----|------|------------|
| 1 | QR Payment | Fraud false positive | 72 | Blocks legitimate payments | Rule tuning, monthly review, < 5% FP target |
| 2 | P2P Transfer | Stale balance read | 54 | Over-debit (money creation) | No cache for writes, `SELECT FOR UPDATE` |
| 3 | Auth | Token theft | 54 | Unauthorized access | 15-min token, refresh rotation, device binding |
| 4 | Settlement | Missing transactions | 48 | Merchant underpayment | Three-way reconciliation, completeness check |
| 5 | Top-Up | Lost callback | 42 | Wallet not credited | Polling reconciliation (15 min), bank statement daily |
| 6 | Auth | OTP brute force | 40 | Account compromise | 3 attempts/OTP, 5 OTPs/hour, account lock |
| 7 | Auth | PIN timing attack | 40 | PIN extraction | Constant-time comparison (Argon2id) |
| 8 | Top-Up | Duplicate callback | 40 | Double credit (free money) | Inbox deduplication, idempotency |

---

## 9. Financial Integrity Risks

### 9.1 Balance Drift Risks

| Risk | Cause | Impact | Probability | Detection | Mitigation |
|------|-------|--------|-------------|-----------|-----------|
| **Wallet balance ≠ ledger sum** | Bug in debit/credit logic, partial transaction | Users see incorrect balance, financial loss | Low | Reconciliation job (every 15 min) | Wallet + Ledger co-located in same DB, atomic transactions |
| **Shadow balance divergence** | Redis cached balance diverges from DB truth | Incorrect balance shown in app, failed transactions | Medium | Balance comparison alert (cached vs DB) | Write-through cache invalidation, DB is always source of truth for writes |
| **Float balance drift** | Platform float account ≠ sum(all user wallets) | Regulatory violation, financial audit failure | Low | Daily float reconciliation | Dedicated float account in ledger, automated daily check |
| **Negative balance** | Race condition or bug allows over-debit | Money created from nothing | Very Low | DB constraint `balance >= 0`, reconciliation | `CHECK (balance >= 0)` constraint, `SELECT FOR UPDATE` |
| **Orphaned hold/escrow** | Payment fails mid-flow, hold not released | User funds locked permanently | Medium | Hold age alert (> 1 hour for pending holds) | TTL on holds (auto-release after 30 min), reconciliation job to release stale holds |

### 9.2 Double-Entry Violation Risks

| Violation Type | Scenario | Detection | Response |
|---------------|----------|-----------|----------|
| **Debit without credit** | Bug creates one side of journal entry | `SUM(debit) ≠ SUM(credit)` per journal check | P0 alert → halt payments → compensating entry |
| **Mismatched amounts** | Debit ₫100K but credit ₫99K (rounding bug) | Reconciliation tolerance check (diff must be ₫0) | Alert → investigate → fix entry |
| **Wrong account credited** | Mapping error sends credit to wrong wallet | User complaint, reconciliation mismatch | Audit trail → reverse → re-credit correct account |
| **Duplicate entry** | Idempotency failure creates two debit-credit pairs | Transaction count anomaly | Inbox deduplication, reverse duplicate |
| **Backdated entry** | Bug creates entry with past timestamp | Temporal integrity check | All timestamps from DB `NOW()`, never application clock |

### 9.3 Refund Integrity Risks

| Risk | Scenario | Control |
|------|----------|---------|
| **Over-refund** | Refund amount > original transaction amount | Amount validation: `refund_amount <= original_amount - previous_refunds` |
| **Double refund** | Same transaction refunded twice | Idempotency on `(original_txn_id, refund_request_id)`, state machine enforcement |
| **Refund after settlement** | Refund issued after merchant has been paid out | Settlement hold period, clawback mechanism from future settlements |
| **Partial refund precision** | Multiple partial refunds don't sum to correct total | Banker's rounding, running total tracked, final refund = remainder |
| **Refund to wrong wallet** | Bug credits refund to different user | Validation: `refund_recipient == original_payer` |

### 9.4 Settlement Financial Risks

| Risk | Impact | Probability | Control |
|------|--------|-------------|---------|
| **Over-settlement** | Platform pays merchant more than earned | Loss of platform funds | Pre-settlement reconciliation, three-way match |
| **Under-settlement** | Merchant receives less than earned | Merchant dispute, reputation damage | Transaction completeness check, count verification |
| **Settlement to wrong bank account** | Funds sent to incorrect destination | Direct financial loss | Bank account verified during merchant onboarding, no changes allowed without verification |
| **Net settlement calculation error** | Fees not correctly deducted | Revenue loss or merchant overcharge | Fee rules in config, fee line-item in settlement report, reconciliation |
| **FX rate risk (future)** | Currency conversion loss | Financial loss on cross-border | Lock FX rate at transaction time, not settlement time |

### 9.5 Currency & Rounding Risks

| Risk | Control |
|------|---------|
| All amounts stored as **integer (₫, smallest unit)** | No floating-point math for money — ever |
| Rounding rule: **Banker's rounding** (round half to even) | Consistent across all services |
| Fee calculation: `fee = FLOOR(amount × rate)` | Platform always rounds in platform's favor for fees |
| Partial amounts: track running total per operation | `remaining = original - SUM(partials)`, last partial = remaining |
| Multi-currency (future): amounts stored with **currency code + amount** | Never assume VND, always explicit |

---

## 10. Messaging / Event Risks

### 10.1 Event Ordering Risks

| Risk | Scenario | Impact | Control |
|------|----------|--------|---------|
| **Out-of-order processing** | `PAYMENT_COMPLETED` arrives before `PAYMENT_CREATED` | Consumer crashes or creates invalid state | Events carry full state (not deltas), consumers handle idempotent replay |
| **Cross-partition ordering** | Events for same aggregate land on different partitions | Balance operations processed in wrong order | Partition key = `aggregate_id` (all events for same entity → same partition) |
| **Consumer group rebalance reordering** | Rebalance reassigns partition mid-batch | Some events re-delivered, others skipped | Commit after processing (not before), inbox deduplication |
| **Time-based ordering assumption** | Consumer assumes timestamp ordering = causal ordering | Incorrect state derived | Use event sequence numbers (monotonic per aggregate), not timestamps |

### 10.2 Backpressure & Lag Risks

| Risk | Trigger | Detection | Response |
|------|---------|-----------|----------|
| **Consumer lag spike** | Consumer processing slower than producer (e.g., DB slowdown) | Consumer lag metric > 1000 messages per partition | Alert → investigate root cause → scale consumers |
| **Lag causes stale reads** | Notification service 30 min behind events | Users don't receive timely notifications | Separate consumer groups per urgency, priority queue for financial notifications |
| **Backpressure causes producer block** | Kafka broker disk full, producers can't write | `acks=all` times out, outbox entries accumulate | Disk space alerts (> 80%), topic retention tuning, broker scaling |
| **DLQ overflow** | Bad messages accumulate, DLQ grows unbounded | DLQ size alert, unprocessed poisoned messages | DLQ size limit, automated DLQ processing/archival, alert on DLQ ingestion rate |

### 10.3 Schema Evolution Risks

| Risk | Scenario | Control |
|------|----------|---------|
| **Breaking change** | Removing required field from event schema | Schema registry enforces backward compatibility — reject breaking changes |
| **Unknown field in consumer** | New field added by producer, old consumer ignores | Consumers use `ignoreUnknown: true` — tolerant reader pattern |
| **Type change** | Field changes from `string` to `number` | Schema registry rejects type changes — require new event version |
| **Semantic change** | Field meaning changes but name stays same | ADR required for semantic changes, versioned events (v1, v2) |
| **Schema registry unavailable** | Producer can't validate schema, event rejected | Producer caches last-known schema, circuit breaker, fail-open for non-critical events |

### 10.4 Event Infrastructure Risks

| Risk | Probability | Impact | Control |
|------|-------------|--------|---------|
| **Outbox table grows unbounded** | Medium | DB performance degradation from millions of unarchived rows | Outbox cleanup job (archive after publish confirmed, retain 7 days), partitioned outbox table |
| **Relay single point of failure** | Medium | Events stop flowing to Kafka | Multiple relay instances with leader election, health monitoring, auto-restart |
| **Topic auto-creation** | Low | Typo creates wrong topic, messages lost | Disable `auto.create.topics.enable`, all topics pre-created via IaC |
| **Retention expiry loss** | Low | Old events deleted before consumer processes them | Monitor consumer lag vs retention, increase retention for critical topics (30d for payments, 7d for notifications) |
| **Message too large** | Low | Producer serialization failure | Max message size 1 MB, large payloads stored in S3 with reference in event |

---

## 11. Operational Risks (Expanded)

### 11.1 Deployment Risks

| Risk | Severity | Mitigation Strategy |
|------|----------|-------------------|
| **Backward-incompatible API change** | High | API versioning (URL path: `/v1/`, `/v2/`), deprecation notice 90 days before removal, integration tests for all versions |
| **Database migration failure** | Critical | Expand-contract pattern, zero-downtime migrations, staging validation, rollback script for every migration |
| **Container image vulnerability** | Medium | Image scanning in CI (Trivy/Snyk), base image pinning, auto-rebuild on CVE alerts |
| **Deployment during peak** | High | Deployment windows (avoid 11 AM–1 PM, 6–9 PM ICT), manual override for emergencies only |
| **Canary metric false-negative** | High | Multiple canary signals (error rate + latency + business metrics), minimum 5-min observation per stage |

### 11.2 Configuration Drift Risks

| Risk | Detection | Prevention |
|------|-----------|-----------|
| **Manual infra change** | IaC drift detection (Terraform plan on schedule) | No `kubectl edit` in production, all changes via GitOps |
| **Environment config divergence** | Config comparison job (staging vs prod structure) | Config templates with env-specific overrides only for secrets/endpoints |
| **Feature flag inconsistency** | Flag audit (list all flags, check for stale ones) | Flag expiry policy (remove after 30 days of 100% rollout) |
| **Rate limit misconfiguration** | Rate limit test suite | Rate limits in version-controlled config, change requires PR review |
| **Secret not rotated** | Secret age monitoring | Automated rotation, alert when secret age > policy threshold |

### 11.3 Capacity & Resource Risks

| Risk | Early Warning | Response |
|------|-------------|----------|
| **CPU saturation** | CPU > 70% sustained for 5 min | HPA scales up, alert if > 85% |
| **Memory leak** | RSS growing linearly over days (no plateau) | Alert on memory trend, investigate, restart as temporary fix |
| **Disk I/O bottleneck** | IOPS > 80% of provisioned | Scale storage IOPS (gp3 adjustable), investigate query patterns |
| **Network bandwidth** | Inter-AZ traffic cost spike | Optimize serialization size, reduce cross-AZ calls, cache aggressively |
| **Connection pool starvation** | Available connections < 20% pool size | PgBouncer tuning, increase pool, investigate long-running queries |

### 11.4 Dependency & Integration Risks

| Dependency | Failure Mode | Blast Radius | Fallback |
|-----------|-------------|-------------|----------|
| **PostgreSQL managed service** | Upgrade causes brief downtime | All DB-dependent services | Multi-AZ failover, maintenance window scheduling |
| **Redis managed service** | Memory eviction under load | Cache misses, rate limiting failure | Graceful degradation, DB fallback for rate limits |
| **Kubernetes control plane** | API server unavailable | No new deployments, no HPA | Running pods unaffected, manual node management |
| **Container registry** | Image pull failure | New deployments fail | Local image cache on nodes, multi-registry |
| **Monitoring stack** (Prometheus) | Scrape failures | Blind spot in alerting | Duplicate alerting (CloudWatch as backup), synthetic probes |

---

## 12. Risk Severity Matrix

### 12.1 Formal 5×5 Risk Matrix

```
                        IMPACT
                 Low    Medium    High    Critical    Catastrophic
            ┌─────────┬─────────┬─────────┬─────────┬─────────┐
Rare        │  ACCEPT │  ACCEPT │ MONITOR │ MITIGATE│ MITIGATE│
(< 1%/yr)   │   (1)   │   (2)   │   (3)   │   (4)   │   (5)   │
            ├─────────┼─────────┼─────────┼─────────┼─────────┤
Unlikely    │  ACCEPT │ MONITOR │ MITIGATE│ MITIGATE│  BLOCK  │
(1-5%/yr)   │   (2)   │   (4)   │   (6)   │   (8)   │  (10)   │
            ├─────────┼─────────┼─────────┼─────────┼─────────┤
Possible    │ MONITOR │ MITIGATE│ MITIGATE│  BLOCK  │  BLOCK  │
(5-20%/yr)  │   (3)   │   (6)   │   (9)   │  (12)   │  (15)   │
            ├─────────┼─────────┼─────────┼─────────┼─────────┤
Likely      │ MONITOR │ MITIGATE│  BLOCK  │  BLOCK  │  BLOCK  │
(20-50%/yr) │   (4)   │   (8)   │  (12)   │  (16)   │  (20)   │
            ├─────────┼─────────┼─────────┼─────────┼─────────┤
Almost      │ MITIGATE│  BLOCK  │  BLOCK  │  BLOCK  │  BLOCK  │
Certain     │   (5)   │  (10)   │  (15)   │  (20)   │  (25)   │
            └─────────┴─────────┴─────────┴─────────┴─────────┘
```

### 12.2 Response SLA per Risk Level

| Level | Score | Response Time | Action Required | Escalation |
|-------|-------|-------------|-----------------|------------|
| **ACCEPT** | 1–3 | No action needed | Log in risk register, review annually | None |
| **MONITOR** | 4–6 | Review within 30 days | Add monitoring/alerting, review quarterly | Eng Lead |
| **MITIGATE** | 7–12 | Resolve within current quarter | Design + implement controls, assign owner | Eng Manager |
| **BLOCK** | 13–25 | Resolve before launch or immediately | **Blocks launch** until resolved, exec review | CTO / VP Engineering |

### 12.3 Risk Classification by Financial Impact

| Category | Financial Threshold | Example Risks | Response |
|----------|-------------------|---------------|----------|
| **Negligible** | < ₫5M ($200) per incident | Notification delay, UI bug | Fix in normal sprint |
| **Minor** | ₫5M–₫50M ($200–$2K) | Single-user balance error, minor reconciliation diff | P2 fix within 1 sprint |
| **Moderate** | ₫50M–₫500M ($2K–$20K) | Batch settlement miscalculation, fraud bypass | P1 fix within 1 week |
| **Major** | ₫500M–₫5B ($20K–$200K) | DB corruption affecting many users, bank integration failure | P0 immediate response |
| **Catastrophic** | > ₫5B ($200K+) | Full data breach, ledger compromise, regulatory shutdown | War room, exec involvement, regulator notification |

---

## 13. Risk → Architecture Mapping

### 13.1 How Risks Drive Architecture Decisions

| Risk | Architecture Decision | Rationale |
|------|----------------------|-----------|
| **TR-01** DB SPOF | Synchronous replication + automated failover | RPO=0 for financial data, RTO < 5 min |
| **TR-02** Ledger inconsistency | Wallet + Ledger co-located in same DB | Atomic transactions guarantee consistency without distributed TX |
| **TR-03** Kafka message loss | Outbox pattern + inbox deduplication | At-least-once delivery guaranteed by DB persistence |
| **TR-04** Scalability cliff | PgBouncer + read replicas + partition-ready schema | Defers sharding complexity to Year 2 while handling Year 1 load |
| **TR-09** Cascading failure | Circuit breakers + bulkheads + timeouts per call | Isolates failures, prevents whole-system collapse |
| **BR-02** Fraud bypass | Multi-layer fraud (velocity + rules + device + geo) | No single layer is sufficient; defense-in-depth |
| **BR-03** Bank dependency | Bank abstraction layer + multi-bank support | Hot failover between banks without code changes |
| **BR-04** PII exposure | PCI zone + field-level encryption + data masking | Defense-in-depth: network isolation AND data-level protection |
| **OR-01** Bad deployment | Canary deployment + auto-rollback | Limit blast radius to 5% of traffic, auto-detect regression |
| **OR-07** Operational mistake | IaC-only, soft delete, PITR | Multiple recovery layers for human error |
| **OR-08** Region outage | Multi-AZ + DR region | Survive AZ failure (common) and region failure (rare) |

### 13.2 Risk-Based Service Boundary Decisions

| Decision | Driven by Risk | Alternative Considered | Why Not |
|----------|---------------|----------------------|---------|
| **Wallet + Ledger → same DB** | TR-02 (ledger inconsistency) | Separate DBs with saga | Saga adds partial failure states, eventually-consistent ledger is unacceptable for financial data |
| **Fraud → separate service** | BR-02 (fraud loss) | Inline fraud check in Payment | Separate service allows independent scaling, rule updates without payment deployment |
| **Bank Integration → separate service** | BR-03 (bank dependency) | Direct bank calls from Payment | Abstraction layer enables multi-bank failover, circuit breaker per bank |
| **Notification → async (Kafka)** | Service-to-service coupling | Sync HTTP call to Notification | Async decouples sender from delivery, notification failure doesn't block payment |
| **Settlement → batch (not real-time)** | Financial integrity, reconciliation | Real-time settlement per transaction | Batch allows pre-settlement reconciliation, catch errors before money leaves platform |

### 13.3 Risk-Based Technology Decisions

| Technology | Risk Addressed | Alternative | Why Chosen |
|-----------|---------------|-------------|-----------|
| **PostgreSQL** (not NoSQL) | Financial integrity, ACID | DynamoDB, MongoDB | ACID transactions required for double-entry ledger |
| **Kafka** (not RabbitMQ) | Event durability, replay | RabbitMQ, SQS | Durable log, replay capability, high throughput, ordered by partition |
| **Redis** (not Memcached) | Rate limiting, distributed locks | Memcached | Lua scripting for atomic rate limit operations, Sentinel for HA |
| **KMS** (not app-level keys) | Key compromise | Application-managed keys | Key material never leaves HSM, audit trail, automated rotation |
| **EKS** (not ECS/bare EC2) | Operational complexity | ECS, EC2 ASG | mTLS via cert-manager, HPA, service mesh ready, infrastructure standardization |

---

## 14. Disaster Scenarios

### 14.1 Cascading Failure: DB Slowdown → Full Platform Outage

```
Timeline:
T+0:          wallet_db primary: long-running query causes lock contention
T+10s:        Wallet service response times: 50ms → 2000ms
T+15s:        Connection pool exhausted (all 50 connections in use)
T+20s:        Payment service: wallet calls timeout → errors → circuit breaker OPEN
T+25s:        Fraud service: wallet balance check fails → fail-closed → all payments blocked
T+30s:        Transaction service: errors spike → Kafka consumer lag increases
T+45s:        API Gateway: 503 errors → client retries → 3× traffic amplification
T+60s:        Auto-scaling: spawns new instances → MORE connections to DB → worse
T+90s:        Alert: P0 fired (error rate > 50%)
T+2min:       On-call kills long-running query, connection pool recovers
T+3min:       Circuit breakers close, services recover
```

| Aspect | Detail |
|--------|--------|
| **Root cause** | Single long-running query (analytics on prod?) |
| **Cascade path** | DB → Wallet → Payment/Fraud → all services → user-visible outage |
| **Amplifier** | Client retries + auto-scaling both make it worse |
| **Detection** | Connection pool utilization alert, query duration alert, error rate |
| **Mitigation** | Query timeout (30s kill), PgBouncer connection limits, disable auto-scaling ceiling, separate analytics to read replica |

### 14.2 Multi-Component Failure: Kafka + Redis Down Simultaneously

```
Timeline:
T+0:          AZ-b network partition isolates Kafka broker 2 + Redis node 2
T+5s:         Kafka: ISR shrinks (2→1 for affected partitions), still functional
T+5s:         Redis: Sentinel detects node down, starts failover
T+10s:        Redis failover complete (new primary in AZ-a)
T+15s:        Kafka: leader election for affected partitions → brief pause in consumption
T+20s:        Consumers resume, some messages re-delivered (handled by inbox dedup)
T+30s:        System functional but degraded (single-AZ for Kafka, Redis recovering)
T+1h:         AZ-b network restored, Kafka ISR catches up, Redis cluster rebalanced
```

| Aspect | Detail |
|--------|--------|
| **Root cause** | AZ network partition (AWS infrastructure issue) |
| **Key insight** | Multi-AZ deployment means no single AZ failure causes outage |
| **Real risk** | If Kafka had `min.insync.replicas=1`, data loss possible. Our `min.insync.replicas=2` prevents writes during ISR=1 (fail-safe) |
| **Trade-off** | With `min.insync.replicas=2` and ISR=1, producers block → payments pause until ISR recovers. Acceptable for financial integrity |

### 14.3 Black Swan: Supply Chain Attack on Dependencies

```
Timeline:
T+0:          Compromised NPM package published (dependency of our logging library)
T+0-12h:      CI builds pull compromised package
T+12h:        Security advisory published
T+12h+30m:    Snyk/Dependabot alerts trigger
T+12h+45m:    Lock package version, rollback to last known good build
T+13h:        Assess impact: what did the compromised code do?
T+13h-24h:    Forensic analysis, credential rotation if needed
```

| Aspect | Detail |
|--------|--------|
| **Root cause** | Compromised upstream dependency (not our code) |
| **Detection** | Dependency scanning (Snyk/Dependabot), security advisory feeds |
| **Mitigation** | Lock file enforcement (`package-lock.json`), CI verify integrity (hash check), pin major versions, private registry (Verdaccio/Artifactory) as proxy with caching |
| **Recovery** | Rollback to last clean build, rotate all credentials (assume compromised), forensic analysis |

### 14.4 Data Center Fire / Physical Destruction

```
Timeline:
T+0:          Data center hosting AZ-a destroyed
T+0:          Services in AZ-a: down. AZ-b: still running (multi-AZ deployment)
T+5s:         ALB health checks fail for AZ-a targets, traffic shifts to AZ-b
T+10s:        DB: standby in AZ-b promoted (if primary was in AZ-a)
T+30s:        System running on single AZ (degraded capacity ~50%)
T+1min:       HPA scales within AZ-b to handle full traffic
T+5min:       System at full capacity on single AZ
T+24h-7d:     AWS provisions replacement capacity
```

| Aspect | Detail |
|--------|--------|
| **Key insight** | Multi-AZ architecture survives single AZ destruction |
| **Data safety** | Synchronous replication to standby in other AZ → RPO = 0 |
| **Capacity risk** | Single AZ handling full traffic → may need larger instances temporarily |
| **Cross-region backup** | Even if entire region destroyed, cross-region backups survive (S3 CRR, DB snapshot replication) |

### 14.5 Sustained DDoS + Ransom Attack

```
Timeline:
T+0:          Volumetric DDoS: 10 Gbps against API endpoints
T+0:          Application-layer DDoS: credential stuffing at 100K req/min
T+1min:       AWS Shield activates (automatic for L3/L4)
T+2min:       WAF rate limiting blocks >1000 req/min per IP
T+5min:       Attacker adapts: distributed across 50K IPs
T+10min:      Geo-blocking non-Vietnam IPs (90% of users are domestic)
T+15min:      Attacker pivots: ransom note received
T+20min:      Incident commander: engage AWS Shield Advanced, notify law enforcement
T+1h:         Traffic normalized with enhanced WAF rules
T+24h:        Post-mortem, permanent WAF rule updates
```

| Aspect | Detail |
|--------|--------|
| **Layer 3/4** | AWS Shield Standard (automatic), Shield Advanced for enhanced coverage |
| **Layer 7** | WAF rules, rate limiting, geo-blocking, CAPTCHA challenge for suspicious traffic |
| **Ransom response** | Never pay ransom, engage law enforcement, ensure backups are immutable and accessible |
| **Architecture resilience** | Auto-scaling absorbs moderate spikes, rate limiting protects backend |

---

## 15. Reconciliation Strategy Risks

### 15.1 Three-Way Reconciliation Failure Modes

```
                    ┌──────────┐
                    │  WALLET  │ ←── User-facing balance
                    │  BALANCE │
                    └────┬─────┘
                         │
                    Must equal
                         │
                    ┌────┴─────┐
                    │  LEDGER  │ ←── Double-entry journal
                    │   SUM    │
                    └────┬─────┘
                         │
                    Must equal
                         │
                    ┌────┴─────┐
                    │  BANK    │ ←── External bank statement
                    │ BALANCE  │
                    └──────────┘
```

| Failure Mode | Cause | Detection Window | Resolution |
|-------------|-------|-----------------|-----------|
| **Wallet ≠ Ledger** | Application bug in debit/credit | 15 min (reconciliation job) | P0: halt payments, identify root cause, compensating entry |
| **Ledger ≠ Bank** | Pending bank transactions, callback delays | End of day (bank statement received) | Match pending transactions first, investigate unmatched items |
| **Wallet ≠ Bank** | Combined effect of above | End of day | Investigate via ledger (intermediate source of truth) |
| **Off-by-one timing** | Transaction committed at 23:59:59, bank records at 00:00:01 | T+1 boundary (reconciliation mismatch) | Time-window tolerance (±5 min at day boundary), re-reconcile next day |
| **Partial settlement** | Bank settles 99 of 100 transactions | Settlement report comparison | Individual transaction matching, exception queue for unmatched |

### 15.2 Reconciliation Timing Risks

| Window | Frequency | Risk | Control |
|--------|-----------|------|---------|
| **Wallet ↔ Ledger** | Every 15 min | Drift grows undetected for up to 15 min | Reduce to 5 min for Tier 0 services, instance alert on any diff |
| **Ledger ↔ Bank** | Daily (EOD) | Full day of undetected bank-side issues | Add hourly bank API polling for recent transactions |
| **Settlement ↔ Bank** | Per settlement batch | Settlement report doesn't match transfer | Pre-settlement check: verify total before initiating transfer |
| **Cross-service state** | Hourly | Service A says COMPLETED, Service B says PENDING | State comparison job, event replay for stuck transactions |

### 15.3 Reconciliation Edge Cases

| Edge Case | Scenario | Resolution Strategy |
|-----------|----------|-------------------|
| **Transaction in flight** | Recon runs while transaction is mid-processing | Exclude transactions < 5 min old from reconciliation window |
| **Timezone mismatch** | Bank uses UTC, platform uses ICT | All internal timestamps in UTC, convert for bank matching |
| **Rounding tolerance** | Bank calculates fees slightly differently | Tolerance threshold: ₫0 for individual txn, ₫100 for daily aggregate |
| **Missing bank statement** | Bank API fails to deliver daily statement | Retry + polling + manual download fallback, alert if no statement by 6 AM |
| **Duplicate in bank** | Bank reports same transaction twice | Match on unique bank_reference_id, flag duplicates |
| **Chargebacks** | Bank reverses transaction post-settlement | Chargeback event creates reverse ledger entry, merchant deficit tracked |

### 15.4 Reconciliation Monitoring & Alerting

| Metric | Alert Threshold | Response |
|--------|----------------|----------|
| `recon_wallet_ledger_diff` | Any non-zero value | P0: immediate investigation |
| `recon_ledger_bank_unmatched_count` | > 10 transactions | P1: investigate within 2 hours |
| `recon_unmatched_age_hours` | Any transaction > 24h unmatched | Escalate to finance team |
| `recon_job_runtime` | > 5 min (should be < 1 min) | SRE investigate performance |
| `recon_job_last_success` | > 30 min ago | P0: reconciliation job may be down |
| `settlement_bank_diff` | Any diff > ₫100 | Block settlement, investigate |

---

## 16. Fraud Scenarios (Expanded)

### 16.1 Money Mule Network

```
Scenario:
1. Fraud ring creates 20 accounts with stolen identities (Tier 0/1)
2. Compromised source account tops up ₫200M from stolen bank credentials
3. Source distributes ₫10M each to 20 mule accounts via P2P (rapid-fire)
4. Each mule withdraws ₫10M to different bank accounts within hours
5. By time original theft is detected (24-72h), funds are gone
```

| Detection Signal | Threshold | Action |
|-----------------|-----------|--------|
| New account + immediate large top-up | > ₫10M first top-up within 7 days of registration | Hold + manual review |
| Rapid fan-out (1→many transfers) | > 5 P2P transfers in 10 min from single account | Auto-block, alert fraud team |
| Multiple accounts from same device fingerprint | > 3 accounts from identical device | Flag all accounts for review |
| Withdrawal velocity post-receipt | Receive + withdraw > ₫5M within 1 hour | Hold withdrawal for 24h |
| Graph analysis: connected accounts with similar behavior | Network of accounts with common traits | Batch review, potential ring identification |

### 16.2 Promotional Abuse

```
Scenario:
1. Platform runs "₫50K cashback on first QR payment" promotion
2. Abuser creates 100 fake accounts (unique phone numbers from SIM farm)
3. Each account makes minimum QR payment to own merchant account
4. Each account receives ₫50K cashback → total abuse: ₫5M
5. Funds consolidated and withdrawn
```

| Detection Signal | Threshold | Action |
|-----------------|-----------|--------|
| SIM farm detection | Multiple registrations from sequential phone numbers | Auto-reject, require manual KYC |
| First-purchase pattern | All 100 accounts pay same merchant within 24h | Flag merchant + accounts |
| Cashback velocity | Promo redemptions > 10/day from same device/IP | Block + review |
| Merchant self-payment | QR payment where payer device = merchant device location | Auto-flag, delay cashback |
| Abnormal promo cost | Campaign daily cost > 150% of forecast | Pause campaign, investigate |

### 16.3 Merchant Fraud Patterns

| Pattern | Description | Detection | Response |
|---------|-------------|-----------|----------|
| **Transaction laundering** | Merchant processes transactions for another (unlicensed) business | Transaction description analysis, industry code mismatch | Merchant review, potential termination |
| **Inflated transactions** | Merchant charges ₫100K, service worth ₫10K (credit card laundering) | Average ticket anomaly, customer complaint ratio | Merchant risk review |
| **Refund abuse** | Merchant processes refund to different card/wallet than payer | Refund recipient ≠ original payer validation | Auto-block different-person refunds |
| **Ghost merchant** | Merchant account with no real business presence | No real address/website, KYC document analysis | Enhanced merchant verification for high volume |
| **Settlement manipulation** | Merchant times transactions to cross settlement windows for double-counting | Duplicate transaction detection across settlement batches | Cross-batch deduplication check |

### 16.4 Velocity Attack Bypass Attempts

| Attack | Technique | Counter |
|--------|-----------|---------|
| **Slow drip** | Stay just under velocity limits (₫4.9M of ₫5M daily limit) | Cumulative risk scoring (not just hard limits), ML anomaly detection |
| **Multi-account** | Split transactions across multiple accounts | Graph analysis: linked accounts (same device, same bank, same IP) share velocity budget |
| **Time zone exploitation** | Transact at midnight to reset daily limits | Daily limits based on rolling 24h window, not calendar day |
| **Small then large** | 99 × ₫10K then 1 × ₫10M (triggers only on big transaction) | Cumulative daily velocity + individual transaction limit |
| **Velocity counter reset** | Attempt to reset Redis velocity counters via exploit | Redis ACL (no admin commands), counter persistence to DB as backup |

### 16.5 AML (Anti-Money Laundering) Red Flags

| Red Flag | Indicator | Automated Action |
|----------|-----------|-----------------|
| **Structuring / smurfing** | Multiple transactions just below reporting threshold (₫200M) | Alert if sum > threshold within rolling 7 days |
| **Rapid movement** | Receive → transfer within minutes, no legitimate use | Hold if pass-through ratio > 80% of received funds |
| **High-risk geography** | Transactions from/to sanctioned countries or regions | Block + manual review |
| **Dormant then active** | Account inactive 6+ months, sudden high-volume transactions | Enhanced verification, temporary limits |
| **Round amounts** | Continuous transfers of exact round amounts (₫10M, ₫50M) | Flag pattern, evaluate context |
| **PEP (Politically Exposed Person)** | Account holder matches PEP list | Enhanced due diligence, ongoing monitoring |

### 16.6 Fraud Defense Architecture

```
                 ┌────────────────────────────────────────┐
                 │         FRAUD DEFENSE LAYERS           │
                 │                                        │
    Layer 1:     │  ┌──────────────────────────────────┐  │
    Network      │  │  WAF + Rate Limiting + Geo-block │  │
                 │  └──────────────────────────────────┘  │
                 │                                        │
    Layer 2:     │  ┌──────────────────────────────────┐  │
    Identity     │  │  Device fingerprint + IP scoring  │  │
                 │  │  + SIM swap detection             │  │
                 │  └──────────────────────────────────┘  │
                 │                                        │
    Layer 3:     │  ┌──────────────────────────────────┐  │
    Transaction  │  │  Velocity checks + amount limits  │  │
                 │  │  + pattern rules + sanctions      │  │
                 │  └──────────────────────────────────┘  │
                 │                                        │
    Layer 4:     │  ┌──────────────────────────────────┐  │
    Behavioral   │  │  ML anomaly detection (Year 2)    │  │
                 │  │  + graph analysis + risk scoring  │  │
                 │  └──────────────────────────────────┘  │
                 │                                        │
    Layer 5:     │  ┌──────────────────────────────────┐  │
    Post-event   │  │  Reconciliation + SAR reporting   │  │
                 │  │  + chargeback monitoring          │  │
                 │  └──────────────────────────────────┘  │
                 └────────────────────────────────────────┘
```

---

## 17. Blast Radius Analysis

### 17.1 Per-Component Failure Impact

| Component Down | Users Affected | Revenue Impact | Data Risk | Recovery Time | Cascade Risk |
|---------------|---------------|---------------|-----------|-------------|-------------|
| **wallet_db primary** | 100% (all financial ops) | 100% revenue halt | RPO=0 (sync replica) | RTO < 5 min (auto-failover) | **Critical cascade**: Payment, Fraud, Settlement all fail |
| **Payment Service** | 100% of payment users | 100% payment revenue halt | None (stateless) | RTO < 1 min (HPA restart) | Moderate: notifications delayed, settlement incomplete |
| **Fraud Service** | 100% of payment users (fail-closed) | 100% payment revenue halt | None (stateless) | RTO < 1 min (HPA restart) | **High**: blocks all payments if fail-closed |
| **Wallet Service** | 100% (balance view + all transfers) | 100% revenue halt | None (DB-backed) | RTO < 1 min (HPA restart) | **Critical**: every financial flow depends on wallet |
| **Kafka cluster** | 0% immediately, delayed notifications | Revenue continues (sync paths work) | Events queued in outbox | RTO < 5 min (leader election) | Low short-term, high if prolonged (notifications, settlement) |
| **Redis cluster** | 0% immediately (degraded) | Revenue continues (DB fallback) | None (cache only) | RTO < 30s (Sentinel failover) | Medium: rate limiting degraded, higher latency |
| **Bank Integration** | Top-up + withdrawal users | Partial (P2P still works) | None | Depends on bank (our failover < 1 min) | Low: isolated by abstraction layer |
| **Notification Service** | 0% functionally, UX degraded | None | None | RTO < 1 min | None: fully async, non-blocking |
| **Auth Service** | New logins blocked, existing sessions OK | Partial (active users unaffected) | None | RTO < 1 min | Medium: no new users can transact |
| **API Gateway** | 100% | 100% revenue halt | None | RTO < 30s (ALB health check) | **Critical**: single entry point |

### 17.2 Blast Radius Tiers

```
Tier 0 — PLATFORM DOWN (100% users affected, 100% revenue loss)
├── wallet_db primary failure
├── API Gateway failure
├── Wallet Service failure (cascade → all financial ops)
└── Fraud Service failure (fail-closed → blocks payments)

Tier 1 — MAJOR DEGRADATION (partial functionality lost)
├── Payment Service down (payments blocked, views OK)
├── Auth Service down (new logins blocked)
└── Bank Integration down (top-up/withdrawal blocked)

Tier 2 — MINOR DEGRADATION (UX impact, no revenue loss)
├── Kafka cluster down (notifications delayed)
├── Redis cluster down (higher latency, rate limits degraded)
├── Notification Service down (silent)
└── Settlement Service down (next-day settlement)
```

### 17.3 Blast Radius Containment Strategies

| Strategy | Mechanism | Limits Blast Radius To |
|----------|-----------|----------------------|
| **Circuit breakers** | Open circuit on failure threshold (5 errors in 10s) | Failing service only; callers return fallback |
| **Bulkhead isolation** | Separate thread pools per downstream | One slow dependency doesn't exhaust all threads |
| **Feature flags** | Disable specific feature without full deployment | Affected feature only (e.g., QR payments off, P2P still works) |
| **Rate limiting** | Cap request rate per user/service | Abusive client only; other users unaffected |
| **Canary deployment** | Route 5% traffic to new version | 5% of users if bug in new version |
| **PCI zone isolation** | Network segmentation | Breach contained to PCI zone, admin/public zones unaffected |
| **Database per-service** | Logical isolation (schemas) or physical isolation | DB issue in one service doesn't cascade to others |

---

## 18. Chaos Testing Scenarios

### 18.1 Chaos Testing Strategy

> **Principle**: Break things in a controlled way in staging/pre-prod to discover weaknesses before production does it for you.

**Tools**: AWS Fault Injection Simulator (FIS), Litmus Chaos, custom scripts  
**Environment**: Staging (mandatory), Production (read-only experiments only — e.g., latency injection)  
**Cadence**: Monthly chaos day (staging), quarterly production game day  

### 18.2 Chaos Experiments

| # | Experiment | Injection Method | Hypothesis | Success Criteria | Risk Area |
|---|-----------|-----------------|-----------|-----------------|-----------|
| CE-01 | **Kill wallet_db primary** | FIS: terminate RDS primary instance | Standby promotes automatically, no data loss | Failover < 5 min, RPO=0, app reconnects transparently | TR-01, §17.1 |
| CE-02 | **Kill Kafka broker** | Terminate broker EC2 instance | Leader election occurs, consumers resume, no message loss | Consumer lag spike < 30s, zero message loss (acks=all), producers retry successfully | TR-03, §10 |
| CE-03 | **Evict Redis cache** | `FLUSHALL` command on Redis cluster | Services degrade gracefully, fall back to DB | Latency increase < 3×, no errors, rate limiting falls back | TR-05, §11.4 |
| CE-04 | **Network partition AZ-b** | FIS: disrupt connectivity to AZ-b subnets | Services in AZ-a continue serving, AZ-b isolated | No user-visible errors, Kafka ISR adjusts, DB failover if needed | §14.2, OR-08 |
| CE-05 | **Kill payment-service pods** | `kubectl delete pods` for payment-service | HPA restarts pods, ALB reroutes, no failed payments | Zero failed payments (in-flight transactions retry), restart < 30s | OR-01, §17.1 |
| CE-06 | **CPU stress on wallet-service** | Stress-ng: saturate 2 of 3 pods to 100% CPU | HPA scales up, ALB shifts traffic to healthy pods | Response time < 500ms (SLO), no errors, HPA response < 60s | §11.3 |
| CE-07 | **DNS resolution failure** | FIS: disrupt Route 53 resolution for bank API | Circuit breaker opens, bank calls fail gracefully | Payments to bank blocked (expected), P2P still works, alert fires < 30s | BR-03, §11.4 |
| CE-08 | **mTLS certificate expiry** | Deploy expired cert to one service | Service-to-service calls fail, cert-manager renews | Failure detected < 1 min, auto-renewal triggers, manual rotation < 5 min if needed | OR-06 |
| CE-09 | **Config change: wrong rate limit** | Set rate limit to 1 req/min via config change | Rate limiting blocks all users, alert fires | Alert < 1 min, config rollback < 5 min, total impact < 6 min | §11.2 |
| CE-10 | **Full AZ shutdown** | FIS: stop all instances in AZ-a | Traffic shifts to AZ-b, all services continue | Zero downtime, latency increase < 2×, no data loss | §14.4, OR-08 |

### 18.3 Chaos Testing Progression

```
Phase 1 — Component Level (start here)
├── CE-05: Kill individual pods
├── CE-03: Cache eviction
└── CE-06: CPU stress
     │
Phase 2 — Service Level
├── CE-01: Database failover
├── CE-02: Kafka broker failure
├── CE-07: DNS failure
└── CE-08: Certificate expiry
     │
Phase 3 — Infrastructure Level
├── CE-04: Network partition
├── CE-09: Config change
└── CE-10: Full AZ shutdown
     │
Phase 4 — Game Day (multi-failure)
├── CE-01 + CE-02: DB + Kafka simultaneous
├── CE-04 + CE-06: Network partition + CPU stress
└── Custom: Black Friday simulation (10× traffic)
```

### 18.4 Chaos Testing Results Template

| Field | Content |
|-------|---------|
| **Experiment ID** | CE-XX |
| **Date** | YYYY-MM-DD |
| **Environment** | Staging / Pre-prod |
| **Duration** | X minutes |
| **Hypothesis** | What we expected |
| **Actual Result** | What actually happened |
| **Pass/Fail** | ✅ / ❌ |
| **Findings** | Unexpected behaviors discovered |
| **Action Items** | Fixes needed (linked to risk register) |
| **Follow-up** | Re-test date after fixes |

---

## 19. Risk → Monitoring & Alert Mapping

### 19.1 Financial Risks → Alerts

| Risk | Metric | Alert Rule | Threshold | Severity | Dashboard |
|------|--------|-----------|-----------|----------|-----------|
| **TR-02** Ledger inconsistency | `recon_wallet_ledger_diff` | Any non-zero value | diff ≠ 0 | P0-Critical | Financial Integrity |
| **Balance drift** | `wallet_balance_sum - ledger_sum` | Divergence detected | ≠ 0 for > 1 min | P0-Critical | Financial Integrity |
| **Double payment** | `duplicate_transaction_count` | Duplicate idempotency key processed | > 0 per 5 min | P1-High | Payment Operations |
| **Negative balance** | `wallet_negative_balance_count` | Any wallet with balance < 0 | > 0 | P0-Critical | Financial Integrity |
| **Settlement mismatch** | `settlement_bank_diff` | Settlement ≠ bank transfer | diff > ₫100 | P0-Critical | Settlement |
| **Over-refund** | `refund_exceeds_original_count` | Refund > original transaction | > 0 | P1-High | Payment Operations |
| **Float drift** | `platform_float - sum_user_balances` | Platform float differs from wallet sum | diff > ₫1000 | P0-Critical | Financial Integrity |

### 19.2 Infrastructure Risks → Alerts

| Risk | Metric | Alert Rule | Threshold | Severity | Dashboard |
|------|--------|-----------|-----------|----------|-----------|
| **TR-01** DB SPOF | `rds_replication_lag_seconds` | Replica lag increasing | > 1s for 2 min | P1-High | Database Health |
| **TR-04** Connection exhaustion | `pgbouncer_active_connections / max` | Pool utilization high | > 80% for 3 min | P1-High | Database Health |
| **TR-03** Kafka message loss | `kafka_isr_count` | Under-replicated partitions | ISR < min.insync | P0-Critical | Kafka Health |
| **Consumer lag** | `kafka_consumer_lag` | Consumer falling behind | > 1000 msgs for 5 min | P1-High | Kafka Health |
| **TR-05** Redis failure | `redis_connected_clients` | Connection spike or drop | Drop > 50% in 1 min | P1-High | Cache Health |
| **Disk pressure** | `node_disk_usage_percent` | Disk nearly full | > 85% | P1-High | Infrastructure |
| **CPU saturation** | `container_cpu_usage / limit` | CPU throttling | > 85% for 5 min | P1-High | Service Health |

### 19.3 Security Risks → Alerts

| Risk | Metric | Alert Rule | Threshold | Severity | Dashboard |
|------|--------|-----------|-----------|----------|-----------|
| **BR-02** Fraud | `fraud_blocked_rate` | Fraud block rate anomaly | Spike > 3× baseline | P1-High | Fraud Operations |
| **Brute force** | `auth_failed_attempts_per_user` | OTP/PIN brute force | > 5 failures in 10 min per user | P2-Medium | Security |
| **BR-04** PII exposure | `api_response_pii_detected` | PII in API response (scanner) | > 0 | P0-Critical | Security |
| **BR-09** Insider anomaly | `admin_ops_count_per_admin` | Admin operation spike | > 2× daily average | P1-High | Admin Audit |
| **BR-10** Secret leak | `secret_scan_findings` | Secret detected in code/logs | > 0 | P0-Critical | Security |
| **DDoS** | `waf_blocked_requests_rate` | WAF block rate spike | > 10× baseline in 5 min | P1-High | WAF & Gateway |

### 19.4 Operational Risks → Alerts

| Risk | Metric | Alert Rule | Threshold | Severity | Dashboard |
|------|--------|-----------|-----------|----------|-----------|
| **OR-01** Bad deployment | `canary_error_rate` | Canary version error spike | > 2× baseline in 5 min | P1-High | Deployment |
| **OR-04** Monitoring gap | `prometheus_target_down` | Scrape target unreachable | Any target down > 2 min | P2-Medium | Meta-monitoring |
| **OR-05** Backup failure | `backup_last_success_age_hours` | Backup not completed | > 25h (daily backup) | P1-High | Backup Health |
| **OR-07** Config drift | `terraform_drift_detected` | IaC drift detected | Any drift | P2-Medium | Infrastructure |
| **SLO burn rate** | `slo_burn_rate_1h` | Fast SLO consumption | > 14.4× normal (1h window) | P0-Critical | SLO Dashboard |
| **Error budget** | `error_budget_remaining_percent` | Error budget exhausted | < 10% remaining | P1-High | SLO Dashboard |

### 19.5 Dashboard Architecture

```
┌─────────────────────────────────────────────────────┐
│                 EXECUTIVE OVERVIEW                    │
│  Platform Health │ SLO Status │ Revenue │ Active Users │
├─────────────────────────────────────────────────────┤
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ │
│  │  Financial    │ │  Service     │ │  Security    │ │
│  │  Integrity   │ │  Health      │ │  Posture     │ │
│  │  Dashboard   │ │  Dashboard   │ │  Dashboard   │ │
│  │              │ │              │ │              │ │
│  │ • Wallet/    │ │ • RED per    │ │ • Auth       │ │
│  │   ledger     │ │   service    │ │   failures   │ │
│  │   diff       │ │ • Latency    │ │ • Fraud      │ │
│  │ • Float      │ │   p99        │ │   blocks     │ │
│  │   balance    │ │ • Error      │ │ • WAF        │ │
│  │ • Recon      │ │   rate       │ │   activity   │ │
│  │   status     │ │ • Circuit    │ │ • Admin      │ │
│  │ • Settlement │ │   breaker    │ │   audit      │ │
│  │   status     │ │   state      │ │   anomaly    │ │
│  └──────────────┘ └──────────────┘ └──────────────┘ │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ │
│  │  Kafka &     │ │  Database    │ │  Deployment  │ │
│  │  Events      │ │  Health      │ │  & SLO       │ │
│  │  Dashboard   │ │  Dashboard   │ │  Dashboard   │ │
│  └──────────────┘ └──────────────┘ └──────────────┘ │
└─────────────────────────────────────────────────────┘
```

---

## 20. Incident Runbook Mapping

### 20.1 Risk-to-Runbook Index

| Risk ID | Risk Name | Runbook ID | Runbook Title | Severity | Response Time |
|---------|-----------|-----------|---------------|----------|--------------|
| TR-01 | DB primary failure | RB-001 | Database Failover Procedure | P0 | < 5 min |
| TR-02 | Ledger inconsistency | RB-002 | Ledger Reconciliation Emergency | P0 | Immediate |
| TR-03 | Kafka message loss | RB-003 | Kafka Recovery & Event Replay | P1 | < 15 min |
| TR-04 | Connection exhaustion | RB-004 | PgBouncer Emergency Scaling | P1 | < 10 min |
| TR-09 | Cascading failure | RB-005 | Cascade Containment Procedure | P0 | Immediate |
| BR-02 | Fraud spike | RB-006 | Fraud Incident Response | P1 | < 15 min |
| BR-04 | PII exposure | RB-007 | Data Breach Response | P0 | Immediate |
| BR-08 | Cost explosion | RB-008 | Cost Anomaly Response | P1 | < 30 min |
| BR-09 | Insider attack | RB-009 | Insider Threat Response | P0 | Immediate |
| BR-10 | Key compromise | RB-010 | Emergency Key Rotation | P0 | < 15 min |
| OR-01 | Bad deployment | RB-011 | Deployment Rollback Procedure | P1 | < 5 min |
| OR-05 | Backup restore needed | RB-012 | PITR Restore Procedure | P0 | < 60 min |
| OR-07 | Operational mistake | RB-013 | Data Recovery from Human Error | P0 | < 30 min |
| OR-08 | Region outage | RB-014 | DR Failover Procedure | P0 | < 15 min |

### 20.2 Critical Runbook Summaries

#### RB-001: Database Failover Procedure

```
TRIGGER:  Alert — rds_primary_health_check FAILED
SEVERITY: P0
ON-CALL:  SRE

Steps:
1. VERIFY: Check RDS console — is primary truly down? (1 min)
2. CHECK: Is automated failover in progress? (RDS Multi-AZ auto-promotes)
3. IF auto-failover not triggered:
   a. Manually promote standby: `aws rds failover-db-cluster`
   b. Wait for promotion (typically 1-3 min)
4. VERIFY: Application reconnecting via PgBouncer (check connection count)
5. VERIFY: No data loss — compare last WAL position
6. NOTIFY: Engineering channel — failover complete
7. POST: Create incident ticket, schedule post-mortem
```

#### RB-002: Ledger Reconciliation Emergency

```
TRIGGER:  Alert — recon_wallet_ledger_diff ≠ 0
SEVERITY: P0
ON-CALL:  SRE + Backend Lead

Steps:
1. HALT: Flip feature flag `payments.enabled = false` (< 1 min)
2. IDENTIFY: Which accounts are affected?
   SELECT wallet_id, balance, ledger_sum FROM recon_diff_view;
3. ROOT CAUSE: Check recent deployments, error logs, failed transactions
4. IF bug in code:
   a. Rollback deployment
   b. Create compensating journal entries
5. IF data corruption:
   a. Initiate PITR restore to staging
   b. Extract correct state, apply surgical fix
6. VERIFY: Re-run reconciliation — diff must be ₫0
7. RESUME: Flip feature flag `payments.enabled = true`
8. NOTIFY: Affected users if balance was incorrect
9. POST: Incident report within 24h
```

#### RB-010: Emergency Key Rotation

```
TRIGGER:  Alert — secret_scan_findings > 0 OR manual report of key compromise
SEVERITY: P0
ON-CALL:  Security + SRE

Steps:
1. IDENTIFY: Which key is compromised? (JWT signing, bank API, encryption)
2. FOR JWT signing key:
   a. Create new key version in KMS
   b. Deploy new key ID to all services (rolling restart)
   c. Old key disabled — all existing tokens invalidated
   d. Users must re-authenticate (expected impact)
3. FOR bank API credentials:
   a. Rotate credentials in AWS Secrets Manager
   b. Notify bank partner immediately
   c. Review API call logs for unauthorized access window
4. FOR encryption keys:
   a. Create new key version in KMS (automatic re-encrypt on next access)
   b. Trigger batch re-encryption for affected data
5. AUDIT: Review access logs for the compromise window
6. FIX: Identify and remediate the source of the leak
7. POST: Security incident report, credential rotation confirmation
```

#### RB-014: DR Failover Procedure

```
TRIGGER:  Alert — Route 53 health checks FAILED for all primary region endpoints
SEVERITY: P0
ON-CALL:  SRE Lead + Incident Commander

Steps:
1. VERIFY: Is this a true region outage? Check AWS Health Dashboard (1 min)
2. DECLARE: Incident commander takes charge
3. DNS: Route 53 automated failover (should be automatic, verify)
4. DATABASE: Promote DR region DB replicas to primary
   aws rds failover-db-cluster --db-cluster-identifier dr-wallet-cluster
5. SERVICES: Verify DR region services are running (IaC pre-provisioned)
   kubectl --context dr-region get pods -A
6. KAFKA: Verify MirrorMaker is caught up (Year 2) OR cold start consumers
7. SMOKE TEST: Run automated smoke tests against DR region
8. VERIFY: Core flows working — login, balance check, P2P transfer
9. NOTIFY: Status page updated, engineering channel, exec notification
10. MONITOR: Watch for data gap issues from async replication lag
11. POST-RECOVERY: When primary region returns:
    a. Sync data back from DR to primary
    b. Verify data consistency
    c. Plan failback (scheduled maintenance window)
```

### 20.3 Runbook Maintenance & Testing

| Activity | Frequency | Owner | Output |
|----------|-----------|-------|--------|
| Runbook review | Quarterly | SRE + Service owners | Updated runbook content |
| Runbook drill (tabletop) | Monthly | SRE | Drill report, gaps identified |
| Runbook drill (live, staging) | Quarterly | SRE + all teams | Execution time, success/failure |
| Runbook drill (production, read-only) | Semi-annually | SRE Lead | Validation report |
| Update runbooks after incident | After every P0/P1 incident | Incident owner | Updated runbook reflecting learnings |

---

## 21. Risk Mitigation Roadmap

### 21.1 Pre-Launch (Must Complete)

| Action | Risk IDs | Owner | Deadline |
|--------|----------|-------|----------|
| Implement sync replication + automated failover for wallet_db | TR-01 | SRE | Before Phase 17 (Vertical Slice) |
| Implement same-DB transactions for wallet+ledger | TR-02 | Backend Lead | Phase 18 (Full Build) |
| Set up outbox/inbox pattern with DLQ | TR-03 | Platform | Phase 13 (Platform Core) |
| Implement PgBouncer connection pooling | TR-04, TR-06 | SRE | Phase 12 (Infrastructure) |
| Build multi-bank integration with failover | BR-03 | Backend Lead | Phase 18 |
| Complete KYC tier implementation | BR-01 | Backend Lead | Phase 18 |
| Set up canary deployment pipeline | OR-01 | SRE | Phase 16 (CI/CD) |
| Implement incident response process + runbooks | OR-02 | SRE | Phase 25 (Prod Readiness) |
| Set up automated backup verification | OR-05 | SRE | Phase 12 (Infrastructure) |
| Implement reconciliation service | BR-05 | Backend Lead | Phase 18 |
| Implement three-way reconciliation with alerts | FI, Recon | Backend Lead | Phase 18 |
| Build multi-layer fraud defense (layers 1-3) | BR-02, Fraud | Risk Team | Phase 18 |
| Deploy schema registry for event validation | ME, TR-08 | Platform | Phase 13 |
| Build monitoring dashboards + alert rules | §19 | SRE | Phase 14 (Observability) |
| Write initial runbooks for P0 risks | §20 | SRE | Phase 25 (Prod Readiness) |
| Run Phase 1 chaos experiments (staging) | §18 | SRE | Phase 25 (Prod Readiness) |

### 21.2 Post-Launch (Within 90 Days)

| Action | Risk IDs | Owner | Deadline |
|--------|----------|-------|----------|
| Engage SOC 2 auditor | BR-01 | Compliance | Launch + 30d |
| Schedule first penetration test | BR-04 | Security | Launch + 60d |
| Evaluate secondary eKYC provider | Vendor risk | Business Dev | Launch + 90d |
| First quarterly DR drill | OR-02, OR-05 | SRE | Launch + 90d |
| Fraud rule tuning review (first monthly) | BR-02 | Risk Team | Launch + 30d |
| Reconciliation accuracy review | Recon risks | Finance | Launch + 30d |
| Cost anomaly baseline establishment | BR-08 | SRE + FinOps | Launch + 14d |
| Phase 2 chaos experiments (staging) | §18 | SRE | Launch + 60d |
| First chaos game day (production read-only) | §18 | SRE | Launch + 90d |

### 21.3 Year 2 Planning

| Action | Risk IDs | Owner |
|--------|----------|-------|
| Database partitioning (wallet_db) | TR-04 | SRE + Backend |
| ML-based fraud model (Layer 4) | BR-02 | Risk Team |
| Active-passive multi-region | TR-01, OR-02, OR-08 | SRE |
| Kafka cross-region replication (MirrorMaker) | TR-03 | Platform |
| Graph analysis for fraud network detection | Fraud-16.1 | Risk Team |
| Real-time reconciliation (streaming) | Recon risks | Backend Lead |
| Phase 3-4 chaos experiments (infra + game day) | §18 | SRE |

---

## 22. Risk Review Cadence

| Review | Frequency | Participants | Output |
|--------|-----------|-------------|--------|
| **Risk standup** | Bi-weekly | Risk owners, Eng Lead | Updated risk register, new risks identified |
| **Fraud rule review** | Monthly | Risk Team, Fraud Analyst | Updated fraud rules, false positive review |
| **Reconciliation review** | Monthly | Finance, Backend Lead | Reconciliation accuracy metrics, exception review |
| **Compliance review** | Monthly | Compliance officer, Legal | Compliance gap update, regulatory changes |
| **Chaos experiment review** | Monthly | SRE, Eng Lead | Experiment results, action items |
| **Threat model review** | Quarterly | Security team, Eng Lead | Updated STRIDE, new attack vectors |
| **FMEA review** | Quarterly (or after incident) | Service owners, SRE | Updated RPN scores, new failure modes |
| **Disaster drill** | Quarterly | SRE, all teams | DR drill results, failover validation |
| **Blast radius review** | Quarterly | Architecture, SRE | Updated impact matrix, containment improvements |
| **Full risk re-assessment** | Semi-annually | All stakeholders | Complete risk register refresh |

---

## 23. Connection to Phase 04

**Phase 04 — Domain Design** will use this document to:

| Input (from Phase 03) | Output (Phase 04) |
|----------------------|-------------------|
| STRIDE per service boundary | Service boundary decisions that minimize attack surface |
| SPOF analysis | Redundancy requirements per bounded context |
| Data loss scenarios | Data ownership + replication requirements per context |
| Compliance control mapping | Compliance requirements per bounded context |
| Third-party risk matrix | External integration boundaries + abstraction layer design |
| FMEA for critical flows | Flow-specific service interaction decisions (sync vs async) |
| Detailed scenario analysis | Incident response playbooks and architecture hardening priorities |
| Financial integrity risks | Data co-location decisions (wallet + ledger in same DB) |
| Messaging/event risks | Event bus technology choices and configuration (Kafka settings) |
| Risk→architecture mapping | Direct input to ADR documentation in Phase 04 |
| Reconciliation strategy risks | Reconciliation service design and data flow |
| Fraud scenarios | Fraud service architecture and rule engine design |
| Blast radius analysis | Service isolation and bulkhead design decisions |
| Chaos testing scenarios | Resilience requirements per service |
| Risk→monitoring mapping | Observability requirements for Phase 14 |
| Incident runbook mapping | Operational readiness requirements for Phase 25 |

**Phase 05 — Security Architecture** will expand on the threat model from this phase into concrete security implementation design.

---

### 🛑 APPROVAL GATE — 📋 Document Review

> **Review `03-risk-analysis.md` (v3.1 — Final)**
>
> Phase 03 is **APPROVED** with the following minor improvements added:
> - [x] Blast radius analysis: per-component impact matrix, tier classification, containment strategies
> - [x] Chaos testing scenarios: 10 experiments with progression plan, results template
> - [x] Risk→monitoring/alert mapping: 25+ alert rules across financial, infra, security, and ops
> - [x] Incident runbook mapping: 14 runbooks indexed by risk, 4 critical runbooks detailed
>
> **Phase 03 — COMPLETE. Ready for Phase 04 (Domain Design).**
