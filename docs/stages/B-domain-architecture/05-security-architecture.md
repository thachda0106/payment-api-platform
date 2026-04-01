# Phase 05 — Security Architecture

## MoMo-like Payment API Platform

> **Document Status**: Draft v2.0 — Pending Security Review  
> **Last Updated**: 2026-04-01  
> **Classification**: CONFIDENTIAL — Internal Use Only  
> **Audience**: Security Team, Engineering Leadership, Architecture Review Board, CISO  
> **Input**: Phase 03 — Risk Analysis (v3.1, Approved), Phase 04 — Domain Design (v7.0, Approved)  
> **Author Level**: Principal Security Architect  
> **Approval Gate**: 🔒 Security Review (Security Engineer + Staff Engineer + CISO sign-off)

---

## Table of Contents

1. [Goal & Scope](#1-goal--scope)
2. [Security Principles](#2-security-principles)
3. [Threat Model](#3-threat-model)
4. [Authentication & Session Security](#4-authentication--session-security)
5. [Authorization Model (RBAC + ABAC)](#5-authorization-model)
6. [Service-to-Service Security](#6-service-to-service-security)
7. [Encryption & Key Management](#7-encryption--key-management)
8. [API Security](#8-api-security)
9. [Data Security & Privacy](#9-data-security--privacy)
10. [Network Security](#10-network-security)
11. [Input Validation & Anti-Injection](#11-input-validation--anti-injection)
12. [Secrets Management](#12-secrets-management)
13. [Audit Logging & Forensics](#13-audit-logging--forensics)
14. [Security Monitoring & SIEM](#14-security-monitoring--siem)
15. [Incident Response Plan](#15-incident-response-plan)
16. [Secure SDLC](#16-secure-sdlc)
17. [Compliance & Governance](#17-compliance--governance)
18. [Insider Threat Protection](#18-insider-threat-protection)
19. [Disaster Recovery Security](#19-disaster-recovery-security)
20. [Data Exfiltration Prevention Strategy](#21-data-exfiltration-prevention-strategy)
21. [ADRs (Architecture Decision Records)](#22-adrs)
22. [KPIs & Exit Criteria](#23-kpis--exit-criteria)
23. [Connection to Next Phase](#24-connection-to-next-phase)

---

## 1. Goal & Scope

### 1.1 Goal

Design security by default — shift left. All authentication, authorization, encryption, network security, secrets management, audit logging, incident response, and compliance controls are defined BEFORE architecture decisions are finalized. This document is the **single source of truth** for all security decisions in the platform.

### 1.2 Scope

This document governs security for:

- **17 microservices** across 4 bounded contexts (Identity & Access, Financial Core, Commerce, Platform)
- **All data stores**: PostgreSQL (6 clusters), Redis (cluster), Kafka (cluster), S3, OpenSearch
- **All network boundaries**: Internet-facing, internal service mesh, PCI zone, admin plane
- **All identities**: End users, merchants, admin staff, services, external partners (banks)
- **All environments**: Production, staging, sandbox, DR

### 1.3 Security Inputs from Previous Phases

| Phase | Security Input |
|-------|---------------|
| **Phase 01** | 9 user personas with distinct security needs, 14 user journeys with auth requirements |
| **Phase 02** | Per-service NFR matrix (security levels), SLOs for auth services (99.99%) |
| **Phase 03** | 8 business risks, 8 technical risks, 8 operational risks, STRIDE threat model, FMEA for 5 critical flows |
| **Phase 04** | 4 bounded contexts, service boundaries, data ownership, co-located DB strategy |

### 1.4 Security Outputs to Downstream Phases

| Phase | Security Output |
|-------|---------------|
| **Phase 06 — Architecture** | API Gateway auth, mTLS, circuit breakers, service mesh config |
| **Phase 07 — Data Architecture** | PII encryption (§7), data classification (§9), RLS policies |
| **Phase 08 — API Design** | Auth headers (§8), request signing, rate limiting, CORS |
| **Phase 09 — Event Schema** | PII handling in events: never include raw PII in payloads |
| **Phase 12 — Infrastructure** | Network zones (§10), WAF, security groups, IAM roles |
| **Phase 13 — Platform Core** | Auth middleware, encryption libraries, audit interceptors |
| **Phase 14 — Testing** | Security test suite: SAST, DAST, pen test plan (§16) |

---

## 2. Security Principles

| # | Principle | Description | Enforcement |
|---|-----------|-------------|-------------|
| 1 | **Defense in Depth** | Multiple overlapping security layers — no single point of security failure | WAF → Gateway → Service → DB → Audit |
| 2 | **Least Privilege** | Every identity gets minimum permissions required. Revoked when no longer needed | RBAC+ABAC, IAM policies, DB RLS, quarterly access reviews |
| 3 | **Zero Trust** | Never trust, always verify — every request authenticated and authorized regardless of network location | mTLS + service JWT + RBAC at every hop |
| 4 | **Secure by Default** | Default configurations are secure. Insecure options require explicit opt-in with ADR justification | Security linting in CI, secure defaults in shared libraries |
| 5 | **Fail Closed** | Security failures (auth timeout, fraud check unavailable) block the operation, never allow it | Circuit breaker default = DENY, fraud service fail-closed |
| 6 | **Separation of Duties** | No single person can approve and execute sensitive operations | Maker-checker for financial ops, dual approval for production access |
| 7 | **Auditability** | Every security-relevant action is logged immutably with actor, target, action, timestamp, outcome | Append-only audit log, hash chain, 7-10yr retention |
| 8 | **Assume Breach** | Design systems assuming an attacker is already inside the network | Lateral movement detection, blast radius containment, network microsegmentation |
| 9 | **Data Minimization** | Collect, process, and store only the minimum PII required for each function | Per-service PII inventory, automated PII scanning in CI |
| 10 | **Security as Code** | All security policies, rules, and configurations version-controlled and deployed via CI/CD | OPA policies in Git, WAF rules in Terraform, security tests in pipeline |

---

## 3. Threat Model

### 3.1 Threat Actors

| Actor | Motivation | Capability | Target | Likelihood |
|-------|-----------|-----------|--------|-----------|
| **External Attacker (Script Kiddie)** | Financial gain, opportunistic | Low — automated tools, known CVEs | Public APIs, login endpoints | High |
| **External Attacker (Organized Crime)** | Large-scale fraud, money laundering | High — custom tools, social engineering, SIM swap | Payment flows, account takeover, bank integration | Medium |
| **State-Sponsored Actor** | Espionage, disruption | Very High — zero-days, supply chain attacks, persistent | Infrastructure, data exfiltration | Low |
| **Malicious Insider (Employee)** | Financial gain, revenge | High — legitimate access, knowledge of internals | Customer PII, financial data, fraud rule bypass | Low-Medium |
| **Malicious Insider (Contractor)** | Financial gain | Medium — limited access, less knowledge | Code repositories, staging environments | Low |
| **Compromised Partner (Bank API)** | N/A (compromised, not malicious) | High — trusted connection, callback spoofing | Bank integration, callback processing | Low |
| **Compromised Merchant** | Fraud, refund abuse | Medium — API access, webhook knowledge | Payment API, refund flows, settlement | Medium |
| **Social Engineer** | Account takeover | Medium — phone calls, phishing, SIM swap | Customer support, OTP interception | Medium |

### 3.2 STRIDE Threat Analysis

#### 3.2.1 Spoofing

| # | Threat | Attack Vector | Risk | Mitigation | Detection |
|---|--------|--------------|------|-----------|-----------|
| S1 | **Account takeover via SIM swap** | Attacker ports victim's phone number, intercepts OTP | Critical | Device binding (token tied to device_id), SIM change detection cooldown (48h limit on sensitive ops after SIM change), push notification as secondary factor | Login from new device alert, SIM change event monitoring |
| S2 | **Stolen JWT used on different device** | Attacker extracts JWT from compromised device/network | High | Device binding in JWT claims, short-lived tokens (15 min), refresh token rotation with theft detection | Device mismatch on token use → revoke all sessions, alert |
| S3 | **Service impersonation** | Compromised container sends requests pretending to be another service | High | mTLS with SPIFFE identity, service authorization matrix (allowed_callers[]), network policies restrict pod-to-pod | Certificate SAN mismatch logged and blocked |
| S4 | **Bank callback spoofing** | Attacker sends fake bank callbacks to credit wallets | Critical | HMAC signature verification on all callbacks, source IP allowlist, amount cross-check with original request, idempotency (callback_id dedup) | Callback from unknown IP, signature failure alert |
| S5 | **Admin session hijacking** | Attacker steals admin session cookie/token | Critical | MFA required for all admin actions, VPN-only admin access, IP allowlist for admin panel, session timeout 30 min | Admin login from new IP/device, concurrent session alert |

#### 3.2.2 Tampering

| # | Threat | Attack Vector | Risk | Mitigation | Detection |
|---|--------|--------------|------|-----------|-----------|
| T1 | **Transaction amount modification** | Man-in-the-middle modifies payment amount | Critical | TLS 1.3 everywhere, request signing (HMAC-SHA256), amount re-validation at each service layer | Amount mismatch between gateway log and service log |
| T2 | **Ledger entry modification** | Insider modifies ledger records to hide fraud | Critical | Append-only ledger (no UPDATE/DELETE), hash chain integrity, database user has INSERT-only permission | Hash chain verification job (hourly), `sum(debit) ≠ sum(credit)` alert |
| T3 | **Audit log tampering** | Attacker modifies audit logs to cover tracks | Critical | Append-only with no DELETE permissions, hash chain anchoring, real-time replication to separate account (cross-account S3), S3 Object Lock | Hash chain break detection, log volume anomaly |
| T4 | **Configuration tampering** | Attacker modifies fraud rules or rate limits | High | All config in version control (GitOps), config change requires PR + approval, config change audit events | Config change alert, drift detection (IaC plan) |
| T5 | **Webhook payload tampering** | Attacker modifies webhook content between our system and merchant | Medium | HMAC-SHA256 signature on webhook body, TLS in transit, merchant verifies signature | Signature verification failure logged on merchant side |

#### 3.2.3 Repudiation

| # | Threat | Attack Vector | Risk | Mitigation | Detection |
|---|--------|--------------|------|-----------|-----------|
| R1 | **User denies making a transaction** | User claims they did not authorize a payment | High | PIN verification logged, device_id + IP + geo logged per transaction, audit trail with full context | Audit trail lookup with device, IP, geo correlation |
| R2 | **Admin denies performing an action** | Admin claims they did not freeze an account | High | All admin actions require MFA, audit log with actor identity + MFA confirmation ID, session recording for admin panel | Audit log with MFA evidence, video session replay |
| R3 | **Merchant denies receiving settlement** | Merchant claims they were not paid | Medium | Immutable settlement records with bank transfer reference, signed settlement reports | Bank statement reconciliation, signed PDF receipts |
| R4 | **Service-to-service call denied** | Service claims it did not make a specific call | Low | Correlation ID propagation, distributed tracing (OpenTelemetry), service JWT with unique jti | Trace reconstruction from OpenTelemetry |

#### 3.2.4 Information Disclosure

| # | Threat | Attack Vector | Risk | Mitigation | Detection |
|---|--------|--------------|------|-----------|-----------|
| I1 | **PII leakage in logs** | Sensitive data (phone, national_id) appears in application logs | High | PII redaction middleware on all log output, automated PII scanning in CI, log sampling review | CI pipeline PII scanner, periodic log audit |
| I2 | **PII leakage in error responses** | Stack traces or DB errors reveal internal data | Medium | Generic error responses to clients, detailed errors only in internal logs, never expose DB column names | Error response audit, penetration testing |
| I3 | **Database credential exposure** | DB credentials in env vars, config files, or code | Critical | Secrets Manager (never env vars), pre-commit secret scanning (detect-secrets), CI rejection | Secret scanning alerts, CloudTrail access patterns |
| I4 | **PII in event payloads** | Kafka events contain unencrypted PII | High | Events carry only IDs (never raw PII), PII lookup via service call when needed, event schema validation | Schema registry validation, event payload audit |
| I5 | **Enumeration attacks** | Attacker probes `/auth/login` to discover which phone numbers are registered | Medium | Constant-time responses for both "found" and "not found" cases, rate limiting on auth endpoints | Rate limit trigger on auth endpoint probing patterns |
| I6 | **Memory dump / core dump exposure** | Crash dumps contain in-memory secrets or PII | Medium | Disable core dumps in production, clear sensitive data from memory after use, encrypted swap | Core dump monitoring, memory scrubbing verification |

#### 3.2.5 Denial of Service

| # | Threat | Attack Vector | Risk | Mitigation | Detection |
|---|--------|--------------|------|-----------|-----------|
| D1 | **Volumetric DDoS (Layer 3/4)** | UDP/TCP flood from botnet | High | AWS Shield Advanced (auto-mitigation), ALB scaling, rate limiting | Shield alerts, traffic volume spike |
| D2 | **Application-layer DDoS (Layer 7)** | Slow POST, HTTP flood targeting expensive endpoints | High | WAF rate limiting per IP/user, request timeout enforcement, expensive endpoint protection (Argon2id rate limiting) | WAF block events, request queue depth spike |
| D3 | **OTP flood** | Attacker triggers thousands of OTPs to exhaust SMS budget | Medium | Rate limit: 5 OTPs/phone/hour, 100 OTPs/IP/hour, CAPTCHA after 3 failed attempts | SMS cost anomaly, OTP volume spike |
| D4 | **Search/report DoS** | Attacker triggers expensive database queries via search | Medium | Pagination limits (max 100 per page), query timeout (5s), read replicas for search, search rate limiting | Slow query alerts, read replica lag |
| D5 | **Connection pool exhaustion** | Slow clients hold connections open | Medium | Connection timeout (30s), PgBouncer connection pooling, max connections per service | Connection pool utilization alert >80% |
| D6 | **Kafka consumer lag attack** | Flood of events causes consumer lag, stale financial data | Medium | Per-topic rate limits, consumer lag monitoring, separate consumer groups for critical vs non-critical | Consumer lag > 1000 messages alert |

#### 3.2.6 Elevation of Privilege

| # | Threat | Attack Vector | Risk | Mitigation | Detection |
|---|--------|--------------|------|-----------|-----------|
| E1 | **IDOR (Insecure Direct Object Reference)** | User modifies `wallet_id` in request to access another user's wallet | Critical | All resource access validated: `resource.owner_id == jwt.sub`, PostgreSQL RLS, authorization middleware | RBAC deny events logged, resource access audit |
| E2 | **Role escalation via JWT manipulation** | Attacker modifies JWT claims to elevate role | Critical | RS256 asymmetric signing (cannot forge without KMS private key), token integrity verified at gateway | JWT signature verification failure alert |
| E3 | **Admin privilege escalation** | Compromised support agent account used for admin operations | High | Separate admin identity provider, MFA required, IP allowlist for admin, role cannot be self-escalated, role change requires SUPER_ADMIN + HR approval | Role change audit events, privilege escalation detection |
| E4 | **SQL injection for data access** | Attacker bypasses application logic via SQL injection | Critical | Parameterized queries only (Prisma ORM), no raw SQL allowed, input validation (Zod), WAF SQL injection rules | WAF SQL injection blocks, application error rate spike |
| E5 | **Container escape** | Attacker escapes container to access host or other containers | High | Non-root containers, read-only filesystem, no privileged mode, Kubernetes pod security policies, network policies | Container runtime anomaly detection (Falco), syscall monitoring |
| E6 | **Supply chain attack** | Compromised NPM dependency gains elevated access | High | Dependency scanning (Snyk), lock file enforcement, private registry proxy, minimal runtime dependencies | Dependency vulnerability alerts, new dependency review |

### 3.3 Attack Surface Summary

```
                            ATTACK SURFACE MAP
    
    ┌─────────────────────────────────────────────────────────┐
    │                    EXTERNAL SURFACE                      │
    │                                                          │
    │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐│
    │  │Mobile API │  │Merchant  │  │Admin     │  │Bank     ││
    │  │(REST)    │  │API       │  │Dashboard │  │Callbacks││
    │  │          │  │(REST)    │  │(Web)     │  │(REST)   ││
    │  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬────┘│
    │       │              │              │              │     │
    │  Threats:        Threats:       Threats:      Threats:   │
    │  S1,S2,D1-D3    T5,E1,D2     S5,E3,R2     S4,T1       │
    │  I5,E1,E4       I2           E3,I3        T2           │
    └───────┼──────────┼──────────────┼──────────────┼────────┘
            │          │              │              │
    ┌───────▼──────────▼──────────────▼──────────────▼────────┐
    │                    INTERNAL SURFACE                       │
    │                                                          │
    │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐│
    │  │Services  │  │Databases │  │Message   │  │Secrets  ││
    │  │(17 svcs) │  │(6 PG +   │  │Bus       │  │(KMS +   ││
    │  │          │  │Redis)    │  │(Kafka)   │  │SM)      ││
    │  └──────────┘  └──────────┘  └──────────┘  └─────────┘│
    │  Threats:       Threats:      Threats:      Threats:    │
    │  S3,E5,E6      T2,I3,E4     I4,D6         I3,I6       │
    │  T4,R4         I6           T3                         │
    └─────────────────────────────────────────────────────────┘
```

---

## 4. Authentication & Session Security

### 4.1 Authentication Model

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Protocol** | OAuth 2.0 + custom JWT | Industry standard, extensible, well-tooled |
| **Token format** | JWT (JSON Web Token) | Stateless verification, contains claims, compact |
| **Signing algorithm** | RS256 (RSA + SHA-256) | Asymmetric — private key signs (never leaves KMS), public key verifies (distributed to services) |
| **Access token lifetime** | 15 minutes | Short-lived limits blast radius of token theft (Threat S2) |
| **Refresh token lifetime** | 7 days | Balance between UX and security |
| **Refresh token rotation** | Every refresh issues new pair, old invalidated. Reuse of old token → revoke ALL tokens for session (theft detection) | Detects stolen refresh tokens |
| **PIN hashing** | Argon2id (`memory: 64MB, time: 3, parallelism: 4`) | Memory-hard, resistant to GPU/ASIC attacks, constant-time comparison |
| **OTP** | 6-digit numeric, 5-min expiry, CSPRNG-generated | SBV compliance, SMS delivery constraints |
| **Admin auth** | SSO (SAML 2.0 / OIDC) + MFA (TOTP/WebAuthn) | Separate identity provider for admin, hardware key preferred |

### 4.2 JWT Structure

```json
{
  "header": {
    "alg": "RS256",
    "typ": "JWT",
    "kid": "key-2026-Q1-v2"
  },
  "payload": {
    "sub": "usr_a1b2c3d4",
    "iss": "https://auth.paywallet.vn",
    "aud": "https://api.paywallet.vn",
    "iat": 1711152000,
    "exp": 1711152900,
    "jti": "jwt_unique_id_here",
    "scope": "user",
    "kyc_tier": 1,
    "device_id": "dev_fingerprint_hash",
    "session_id": "sess_uuid",
    "risk_level": "low",
    "auth_methods": ["otp", "pin"],
    "token_version": 3
  }
}
```

| Claim | Purpose | Security Implication |
|-------|---------|---------------------|
| `sub` | User ID (opaque) | Never expose internal DB IDs |
| `kid` | Key version ID | Enables key rotation without invalidating all tokens |
| `jti` | Unique token ID | Enables token revocation/blacklisting |
| `device_id` | Device fingerprint hash | Bind token to device — detect token theft on different device (Threat S2) |
| `session_id` | Session identifier | Enables session revocation (logout all devices) |
| `kyc_tier` | KYC verification level | Enforce tier-based limits without DB lookup on every request |
| `risk_level` | Risk assessment at login | Informs step-up auth decisions |
| `auth_methods` | Methods used for this session | Tracks authentication strength for step-up decisions |
| `token_version` | User's current token version counter | Enables mass revocation by incrementing counter |

### 4.3 Authentication Flows

#### 4.3.1 Registration Flow

```
Client                    API Gateway              Identity Service         Notification
  │                          │                          │                      │
  │  POST /auth/register     │                          │                      │
  │  {phone, name, dob, id}  │                          │                      │
  │─────────────────────────▶│                          │                      │
  │                          │  Validate input (Zod)    │                      │
  │                          │  Rate limit check        │                      │
  │                          │─────────────────────────▶│                      │
  │                          │                          │ Check duplicate phone │
  │                          │                          │ Fraud signal check    │
  │                          │                          │ Generate OTP (CSPRNG) │
  │                          │                          │ Store: hash(OTP),     │
  │                          │                          │   exp=5min, attempts=0│
  │                          │                          │──────────────────────▶│
  │                          │                          │                      │ Send SMS OTP
  │                          │  201 {otp_token}         │                      │
  │◀─────────────────────────│◀─────────────────────────│                      │
  │                          │                          │                      │
  │  POST /auth/verify-otp   │                          │                      │
  │  {otp_token, otp_code}   │                          │                      │
  │─────────────────────────▶│─────────────────────────▶│                      │
  │                          │                          │ Verify OTP            │
  │                          │                          │  (constant-time cmp)  │
  │                          │                          │ Max 3 attempts        │
  │                          │                          │ Create account        │
  │                          │                          │ Create wallet         │
  │                          │                          │ Audit: ACCOUNT_CREATED│
  │                          │  201 {account_id}        │                      │
  │◀─────────────────────────│◀─────────────────────────│                      │
  │                          │                          │                      │
  │  POST /auth/set-pin      │                          │                      │
  │  {pin} (over TLS 1.3)    │                          │                      │
  │─────────────────────────▶│─────────────────────────▶│                      │
  │                          │                          │ Validate PIN policy   │
  │                          │                          │  (not sequential,     │
  │                          │                          │   not repeated,       │
  │                          │                          │   not birthday)       │
  │                          │                          │ Hash PIN (Argon2id)   │
  │                          │                          │ Store hash            │
  │                          │                          │ Audit: PIN_SET        │
  │                          │  200 OK                  │                      │
  │◀─────────────────────────│◀─────────────────────────│                      │
```

**Security controls**:
- OTP: 6-digit, expires 5 min, max 3 attempts per OTP, max 5 OTPs/phone/hour
- OTP generated via CSPRNG (crypto.randomInt), never Math.random
- PIN: hashed with Argon2id, validated against weak PIN policy (no sequential/repeated/DOB patterns)
- All inputs validated via Zod schemas before processing
- Rate limit: 10 registration attempts per IP per hour
- Fraud signal: device fingerprint checked against known fraud devices

#### 4.3.2 Login Flow with Risk-Based Authentication

```
Client                    API Gateway         Identity Service      Fraud Service    Redis
  │                          │                     │                    │             │
  │  POST /auth/login        │                     │                    │             │
  │  {phone, device_info}    │                     │                    │             │
  │─────────────────────────▶│────────────────────▶│                    │             │
  │                          │                     │ Collect signals:   │             │
  │                          │                     │  IP, device, geo,  │             │
  │                          │                     │  time, user_agent  │             │
  │                          │                     │───────────────────▶│             │
  │                          │                     │                    │ Risk score   │
  │                          │                     │                    │ (login risk) │
  │                          │                     │◀───────────────────│             │
  │                          │                     │                    │             │
  │                          │                     │ risk=LOW:  OTP only│             │
  │                          │                     │ risk=MED:  OTP+PIN │             │
  │                          │                     │ risk=HIGH: BLOCK   │             │
  │                          │                     │                    │             │
  │                          │                     │ Generate OTP       │             │
  │                          │                     │────────────────────│────────────▶│
  │                          │                     │                    │ Store OTP   │
  │  200 {otp_token,         │                     │                    │             │
  │   requires_pin: bool}    │                     │                    │             │
  │◀─────────────────────────│◀────────────────────│                    │             │
  │                          │                     │                    │             │
  │  POST /auth/verify-otp   │                     │                    │             │
  │  {otp_token, otp_code,   │                     │                    │             │
  │   pin (if required)}     │                     │                    │             │
  │─────────────────────────▶│────────────────────▶│                    │             │
  │                          │                     │ Verify OTP         │             │
  │                          │                     │ Verify PIN (if MED)│             │
  │                          │                     │ Issue JWT pair     │             │
  │                          │                     │ Store session:     │             │
  │                          │                     │  device, IP, geo   │             │
  │                          │                     │────────────────────│────────────▶│
  │                          │                     │                    │ Session data│
  │  200 {access_token,      │                     │                    │             │
  │   refresh_token,         │                     │                    │             │
  │   expires_in: 900}       │                     │                    │             │
  │◀─────────────────────────│◀────────────────────│                    │             │
```

**Risk-based authentication signals**:

| Signal | Low Risk | Medium Risk | High Risk (Block) |
|--------|----------|-------------|-------------------|
| **Device** | Known device | New device, same OS | Emulator, rooted/jailbroken |
| **IP** | Same country | Different city | TOR/VPN/proxy, known bad IP |
| **Geo** | Normal location | Different region | Impossible travel (>500km in <1h) |
| **Time** | Normal hours | Unusual hours | 3 AM + new device + new IP |
| **Velocity** | Normal | 3+ logins/hour | 10+ failed OTPs in 30 min |
| **SIM** | No change | Recent SIM change (<48h) | SIM change + password reset |

#### 4.3.3 Step-Up Authentication

For high-value operations, the system requires additional authentication beyond the session:

| Operation | Standard Auth | Step-Up Required | Step-Up Method |
|-----------|---------------|-----------------|----------------|
| View balance | JWT only | No | — |
| P2P transfer ≤₫1M | JWT + PIN | No | — |
| P2P transfer >₫1M | JWT + PIN | Yes | OTP re-verification |
| Change PIN | JWT + current PIN | Yes | OTP re-verification |
| Link bank account | JWT + PIN | Yes | OTP + bank verification |
| Withdrawal >₫5M | JWT + PIN | Yes | OTP + 15-min cooling period |
| Admin: freeze account | JWT + MFA | Yes | MFA re-prompt |
| Admin: modify fraud rules | JWT + MFA | Yes | MFA + peer approval |
| Admin: manual balance adjustment | JWT + MFA | Yes | MFA + dual approval (maker-checker) |

```
Step-up flow:

1. User initiates high-value operation
2. Service checks: operation requires step-up?
3. If JWT claim `auth_methods` does not include required method:
   → Return 403 { "step_up_required": "otp", "challenge_id": "xxx" }
4. Client presents step-up challenge (OTP/PIN/MFA)
5. User completes step-up
6. Service issues step-up token (short-lived, 5 min, single-use)
7. Client retries operation with step-up token
8. Service validates step-up token and proceeds
```

#### 4.3.4 Token Refresh Flow (with Theft Detection)

```
Client                    API Gateway              Identity Service         Redis
  │                          │                          │                    │
  │  POST /auth/refresh      │                          │                    │
  │  {refresh_token}         │                          │                    │
  │─────────────────────────▶│─────────────────────────▶│                    │
  │                          │                          │──────────────────▶ │
  │                          │                          │ Check:             │
  │                          │                          │  1. Token not      │
  │                          │                          │     revoked        │
  │                          │                          │  2. Token family   │
  │                          │                          │     is valid       │
  │                          │                          │  3. Device matches │
  │                          │                          │                    │
  │                          │                          │ If OLD token reused│
  │                          │                          │ (already rotated): │
  │                          │                          │  → THEFT DETECTED  │
  │                          │                          │  → Revoke ENTIRE   │
  │                          │                          │    token family    │
  │                          │                          │  → Alert security  │
  │                          │                          │  → Return 401      │
  │                          │                          │                    │
  │                          │                          │ If valid:          │
  │                          │                          │  → Rotate: issue   │
  │                          │                          │    new pair        │
  │                          │                          │  → Invalidate old  │
  │                          │                          │    refresh token   │
  │  200 {new_access_token,  │                          │                    │
  │   new_refresh_token}     │                          │                    │
  │◀─────────────────────────│◀─────────────────────────│                    │
```

**Token family concept**: Each login creates a "token family" (family_id). All refresh tokens from that login share the same family_id. If a rotated-out token is reused, the entire family is revoked — this detects the scenario where an attacker stole a refresh token and both the attacker and legitimate user try to refresh.

#### 4.3.5 Logout & Session Revocation

```
Single device logout:
  POST /auth/logout
  → Blacklist access token jti in Redis (TTL = remaining lifetime)
  → Delete refresh token for this session
  → Delete session record
  → Audit: LOGOUT event

All devices logout:
  POST /auth/logout-all
  → Increment user's token_version in DB
  → Delete ALL sessions for user in Redis
  → All existing JWTs fail validation (token_version mismatch)
  → Audit: LOGOUT_ALL event

Emergency revocation (by admin):
  POST /admin/users/{id}/revoke-sessions
  → Requires: RISK_ANALYST or SUPER_ADMIN role
  → Same as logout-all + freeze account until investigation
  → Audit: EMERGENCY_SESSION_REVOCATION
```

#### 4.3.6 PIN Reset Flow

```
1. POST /auth/pin-reset/request {phone}
   → Rate limit: 3 reset requests per day per phone
   → OTP sent to registered phone
   → Fraud signal: track PIN reset frequency

2. POST /auth/pin-reset/verify {otp_token, otp_code}
   → Verify OTP (constant-time comparison)
   → Issue temporary reset_token (5 min, single-use, signed)
   → Audit: PIN_RESET_INITIATED

3. POST /auth/pin-reset/confirm {reset_token, new_pin}
   → Validate reset_token (not expired, not used, not replayed)
   → Validate new_pin against weak PIN policy
   → Hash new PIN (Argon2id)
   → Invalidate ALL existing sessions (force re-login on all devices)
   → Apply 24h cooling period: no withdrawals >₫1M for 24 hours
   → Audit: PIN_RESET_COMPLETED
   → Notification: "Your PIN was reset. If this wasn't you, contact support immediately."
```

### 4.4 Account Takeover (ATO) Protection

| Layer | Control | Implementation |
|-------|---------|----------------|
| **Prevention** | Risk-based auth at login | Fraud Service evaluates IP, device, geo, velocity, SIM status |
| **Prevention** | Device binding | JWT bound to device_id, new device requires OTP re-verification |
| **Prevention** | SIM swap protection | 48h cooling period on sensitive operations after SIM change detected |
| **Prevention** | PIN policy | Reject weak PINs (sequential, repeated, DOB-derived) |
| **Detection** | Login anomaly detection | Impossible travel, unusual time, new device + new IP combo |
| **Detection** | Behavioral biometrics (future) | Typing patterns, hold patterns, swipe patterns |
| **Detection** | Concurrent session alert | Alert if same user logged in from 2+ devices simultaneously in different geos |
| **Response** | Automatic account freeze | If ATO confidence > 80%: freeze account, notify user via secondary channel |
| **Response** | Force logout all devices | Revoke all sessions, increment token_version |
| **Recovery** | Identity re-verification | KYC re-check required to unfreeze after ATO |

### 4.5 Session Management

| Aspect | Policy |
|--------|--------|
| **Max concurrent sessions** | 3 per user (oldest is revoked when 4th is created) |
| **Session storage** | Redis hash: `session:{session_id}` → `{user_id, device_id, ip, geo, created_at, last_active}` |
| **Session timeout (idle)** | 30 min idle timeout for user sessions, 15 min for admin sessions |
| **Session timeout (absolute)** | 24h absolute timeout (re-login required regardless of activity) |
| **Session fixation prevention** | New session_id generated on authentication, old session_id invalidated |
| **Session data** | Never store sensitive data (PIN, OTP) in session. Session contains only metadata |
| **Admin session** | VPN-only, IP allowlist, MFA required, 15 min idle timeout, no "remember me" |

### 4.6 Service-to-Service Authentication

| Aspect | Decision |
|--------|----------|
| **Protocol** | Mutual TLS (mTLS) via Istio service mesh |
| **Certificate management** | cert-manager + SPIRE for SVID issuance (1h lifetime, auto-rotation) |
| **Service identity** | SPIFFE ID: `spiffe://paywallet.vn/<service-name>` embedded in X.509 SVID SAN |
| **Service JWT** | Internal JWT with `scope: service`, `service_name`, `allowed_targets[]`, `jti` |
| **Verification** | Both mTLS cert AND service JWT required for inter-service calls |
| **Authorization** | Service authorization matrix: `payment-service` can call `wallet-service`, `fraud-service`, `ledger-service` — not `kyc-service` |

```
Service A                                Service B
    │                                        │
    │  mTLS handshake (mutual cert verify)   │
    │  SPIFFE ID verified                    │
    │◄──────────────────────────────────────▶│
    │                                        │
    │  GET /internal/wallets/{id}/balance     │
    │  X-Service-Auth: <service_jwt>         │
    │  X-Correlation-Id: <trace_id>          │
    │  X-Request-Id: <unique_id>             │
    │───────────────────────────────────────▶│
    │                                        │ 1. Verify mTLS cert SAN
    │                                        │ 2. Verify service JWT signature
    │                                        │ 3. Check: caller SPIFFE ID
    │                                        │    matches JWT service_name
    │                                        │ 4. Check: target in JWT
    │                                        │    allowed_targets[]
    │                                        │ 5. Check: caller in
    │                                        │    service_auth_matrix
    │  200 {balance}                         │
    │◀───────────────────────────────────────│
```

**Service authorization matrix**:

| Caller | Allowed Targets |
|--------|----------------|
| `api-gateway` | `identity-service`, `payment-service`, `wallet-service`, `merchant-service`, `transaction-service` |
| `payment-service` | `wallet-service`, `fraud-service`, `limit-service`, `notification-service` |
| `wallet-service` | `ledger-service` (co-located), `notification-service` |
| `settlement-service` | `ledger-service`, `bank-integration-service`, `notification-service` |
| `fraud-service` | `wallet-service` (balance lookup only), `limit-service` |
| `bank-integration-service` | `wallet-service`, `ledger-service`, `notification-service` |
| `merchant-service` | `wallet-service`, `notification-service` |

Any call not in this matrix → **DENY + alert**.

### 4.7 Comprehensive Token Architecture

To govern all internal and external access concisely, the platform normalizes four disparate token lifecycles:

1. **Access Tokens (JWT)**: Used by End-Users. Short-lived (15-min), RS256 signed, carries ABAC context (`kyc_tier`, `auth_methods`).
2. **Refresh Tokens (Opaque)**: Used by End-Users to fetch new JWTs. Long-lived (7-days), single-use rotation, triggers family-revocation upon replay detection.
3. **Service Tokens (SPIFFE mTLS + Service JWT)**: Used internally between microservices. Ultra-short-lived (1-hour), identity bound to the Pod's X.509 SVID.
4. **Merchant API Keys**: Used by B2B Partners. Extremely long-lived, prefixed for secret-scanning detection (e.g., `mkt_live_xxxx`), hashed via Argon2id in the database exactly like passwords (never stored in plaintext).


## 5. Authorization Architecture

### 5.1 Role-Based Access Control (RBAC)

| Role | Description | Access Level |
|------|-------------|--------------|
| `USER` | End consumer | Own data only (`owner_id == sub`) |
| `MERCHANT` | Business entity | Own merchant data + linked transactions |
| `MERCHANT_ADMIN`| Merchant workspace admin | Own merchant settings, API keys |
| `SUPPORT_AGENT` | L1 Customer Support | Read-only (user profile, Tx search) |
| `SUPPORT_LEAD` | L2 Support | Above + freeze/unfreeze account |
| `FINANCE_ADMIN` | Finance/Treasury | Read-only ledger, initiate refunds |
| `RISK_ANALYST` | Risk/Fraud Operations | Fraud rules, risk limits, SARs |
| `PLATFORM_ADMIN`| Engineering Operations | Feature flags, configs, system state |
| `SUPER_ADMIN` | Break-glass / Executive | All permissions |

### 5.2 Attribute-Based Access Control (ABAC)

Beyond static roles, complex operations require dynamic ABAC checks. The authorization engine evaluates:

1. **Amount Limits**: Even if `FINANCE_ADMIN` has role `refund:create`, ABAC enforces `amount <= 50,000,000 VND`. Over this threshold requires step-up approval.
2. **Ownership**: A user can only initiate a transfer if `source_wallet.owner_id == jwt.sub`.
3. **Risk Level**: High-risk users (flagged by Fraud Service) cannot execute high-value withdrawals regardless of balance constraints. ABAC enforces `user_risk_score < 80`.
4. **Time of Day restrictions**: Operational staff (`SUPPORT_AGENT`) can only access PII during localized business hours (unless designated on-call).

### 5.3 Policy Engine Architecture

Authorization logic is decoupled from business logic using a policy engine (e.g., Open Policy Agent - OPA or equivalent CASL definition at gateway/sidecar layer).

```
Request → API Gateway (AuthN)
        → Service (AuthZ Interceptor)
            → Fetch Resource (e.g., Wallet)
            → Evaluate Policy: 
               allow IF (
                  user.role == 'SUPPORT_LEAD' AND 
                  action == 'freeze' AND 
                  target.wallet.status == 'ACTIVE'
               )
        → Proceed to business logic 
```

### 5.4 Dual Control / Maker-Checker Workflows

Sensitive operations require **Separation of Duties (SoD)** via dual control:

- **Refund > 50M VND**: `SUPPORT_LEAD` (Maker) submits → `FINANCE_ADMIN` (Checker) approves.
- **Fraud Rule Adjustment**: `RISK_ANALYST` (Maker) proposes → `RISK_MANAGER` (Checker) approves.
- **Super Admin Actions**: `SUPER_ADMIN` (Maker) attempts action → Peer `SUPER_ADMIN` (Checker) must approve using physical security token within 15 minutes.

---

## 6. Encryption & Key Management

### 6.1 Cryptographic Standards

| Category | Standard | Rationale |
|----------|----------|-----------|
| **In Transit** | TLS 1.3 (fallback TLS 1.2) | Perfect Forward Secrecy everywhere. |
| **At Rest** | AES-256-GCM | Authenticated encryption, high performance. |
| **Hashing (Password/PIN)**| Argon2id | Memory-hard, resistant to GPU/ASIC cracking. |
| **Signatures** | ECDSA (P-256) / RS256 | API request signing, JWT. |
| **Randomness** | Hardware CSPRNG | Unpredictable OTPs and nonces. |

### 6.2 Encryption at Rest & Key Hierarchy (Envelope Encryption)

The platform implements Envelope Encryption using AWS KMS (or equivalent HSM):

1. **Master Key (CMK)**: Managed in AWS KMS (FIPS 140-2 Level 3 HSM). Never leaves the HSM.
2. **Key Encryption Key (KEK)**: Specific to a logical domain (e.g., `Identity_KEK`, `Ledger_KEK`).
3. **Data Encryption Key (DEK)**: Generated per database row or object.

```
How Envelope Encryption Works:
1. Service asks KMS for a new Data Key.
2. KMS returns: [Plaintext_DEK] + [Encrypted_DEK (encrypted by CMK)].
3. Service encrypts PII using Plaintext_DEK via AES-256-GCM.
4. Service discards Plaintext_DEK from memory.
5. Service stores: { "cipher_text": "...", "encrypted_dek": "..." } in DB.
6. To decrypt: Service sends Encrypted_DEK to KMS, gets back Plaintext_DEK, decrypts.
```

### 6.3 TLS and mTLS Strategy

- **External -> Edge**: TLS 1.3 via AWS ALB. Certificates managed by ACM. Strict Transport Security (HSTS) with 1-year max-age.
- **Edge -> Service / Service -> Service**: Mutual TLS (mTLS) enforced by Istio service mesh. Both client and server authenticate using SPIFFE IDs. Certificates have 1-hour TTL, mitigating cert theft.
- **Service -> Database/Cache**: TLS 1.3 required (e.g., PostgreSQL `sslmode=verify-full`).

### 6.4 Key Rotation & Revocation Policy

- **KMS CMK**: Automatic annual rotation. Previous keys remain available for decryption, but new encryptions use the new key material.
- **TLS Certificates (Internal)**: Auto-rotated every 1 hour via SPIRE.
- **JWT Signing Keys**: Rotated every 90 days. Grace period of 24h where old tokens are honored, then forcefully retired.
- **Compromise Procedure**: Immediate cryptographic erasure. Disable CMK, invalidate all dependent DEKs, enforce full session rotation.

---

## 7. Secrets Management

### 7.1 Centralized Vault

All platform secrets (database passwords, API keys, webhook secrets, Slack tokens) are stored in **AWS Secrets Manager / HashiCorp Vault**.
- **No secrets in Code**: Verified by mandatory pre-commit hooks (`trufflehog`, `detect-secrets`).
- **No secrets in Environment Variables**: Hardened containers fetch secrets at runtime via Vault agent sidecars or SDK. Environment variables only store non-sensitive config or Secret ARNs.

### 7.2 Short-Lived Credentials

- PostgreSQL credentials are dynamically generated per application pod via Vault Database Secrets Engine.
- Passwords expire after 1 hour.
- If an attacker gains reading access to the pod memory, the database credential will be useless shortly after.

### 7.3 Secret Access Auditing

Every fetch of a secret from the vault is logged (AWS CloudTrail / Vault Audit Log) providing a forensic trail associating a specific Pod/IAM Role with access to a specific secret at a specific millisecond.

---

## 8. Network Security Architecture

### 8.1 VPC Segmentation & Zones

The network employs strict microsegmentation via AWS VPCs and Kubernetes Network Policies (Zero Trust Networking).

| Zone | Subnet Scope | Role | Access Rules |
|------|--------------|------|--------------|
| **Public Zone** | `10.0.1.0/24` | WAF, ALB, API Gateway | Internet inbound on 443 only. |
| **Application Zone**| `10.0.10.0/24` | Microservices (EKS/ECS) | Target for public zone ALB. Egress to NAT Gateway. |
| **Data Zone** | `10.0.20.0/24` | RDS, Redis, Kafka | Ingress ONLY from Application Zone. NO NAT/Internet egress. |
| **PCI / Core Zone** | `10.0.30.0/24` | Ledger, Bank Integrations | Strict isolation. Talk to DB only. Egress explicitly whitelisted to partner bank IPs. |
| **Admin Bastion** | `10.0.99.0/28` | Emergency Ops | Accessible only via corporate VPN/SSO (e.g. Tailscale/AWS Client VPN). |

### 8.2 Boundary Protection & Firewall Rules

- **Default Deny Strategy**: All Security Groups and Kubernetes Network Policies operate on a default-deny ingress and egress model. Explicit definitions are required (e.g., `payment-service` allowed egress to `wallet-service` port 3000, `RDS` port 5432).
- **Private Endpoints**: AWS PrivateLink is used to communicate with S3, KMS, and Secrets Manager without traffic traversing the public internet.

---

## 9. Application Security

### 9.1 API Gateway Protections

- **AWS WAF**: Active blocking for OWASP Top 10 (SQLi, XSS, CSRF), malicious bots, and geo-ip blocks (allowing only domestic traffic by default unless explicitly configured).
- **Rate Limiting**: Enforced via Redis at the gateway. 
- **Anonymous/Auth endpoints**: 5 req/sec per IP.
  - **Payment APIs**: 20 req/sec per User ID.
- **Replay Attack & Idempotency Protection**: All state-mutating (`POST/PUT/PATCH`) and financial requests mandate an `X-Idempotency-Key` header.
  - The API Gateway / Service mesh intercepts this and cross-checks against a distributed Redis lock (TTL 24h).
  - If identical keys and payloads are detected (replay attack / network retry), the exact previous HTTP response is returned without triggering business logic or ledger modification.
  - Mitigates double-spending attacks and protects against malicious request captures.

### 9.2 Input Validation & Anti-Injection

All input is classified as untrusted.
1. **Schema Validation**: Explicit JSON schema validation (Zod) on Gateway/Service controller. Undefined fields are stripped (`strict` mode).
2. **Type Coercion Prevention**: Strict typing in TypeScript.
3. **ORM Safety**: Prisma ORM enforces parameterized queries organically, eradicating standard SQL injection vectors.
4. **Header validation**: Discard unexpected or oversized HTTP headers to prevent buffer overflows or HTTP request smuggling.

### 9.3 Secure SDLC Integration

- **SAST**: Semgrep and SonarQube run on every PR. PRs fail if High/Critical vulnerabilities are detected.
- **Dependency Scanning**: Dependabot / Snyk audit package.json in CI.
- **Container Scanning**: Trivy scans Docker images in the registry before deployment allowed.
- **Infrastructure as Code (IaC) Scanning**: Checkov and tfsec validate Terraform/CDK against AWS best practices (blocking public S3 buckets, missing KMS keys) prior to merge.
- **DAST**: Weekly automated dynamic scanning of staging environments.

---

## 10. Data Security

### 10.1 PII Protection & Tokenization

Sensitive Personal Information (Identity cards, exact birthdates, raw email/phone) is protected via:
- **Field-Level Encryption**: Application encrypts the specific DB column using Envelope Encryption (KMS). DB Admins querying PostgreSQL see only ciphertext.
- **Blind Indexing**: To enable search (e.g., "Find user by Phone"), a secure hash (HMAC-SHA256 with a pepper) of the phone number is stored in an index column. Exact matches are queried against the HMAC, not the ciphertext.
- **Data Masking**: API responses and Support Agent dashboards dynamically mask data (e.g., `+84 *******890`).

### 10.2 Data Retention Policy

- **Operational Data (Logs, APM)**: 30 days hot storage, 90 days cold.
- **Business/Financial Data (Transactions, Ledger)**: 10 years immutable storage to comply with SBV standards.
- **PII / Account Destruction**: Soft delete on user request, stripped to non-identifying aggregates after 90 day cooling-off period (Right to Erasure / GDPR / PDPA compliance).

---

## 11. Audit Logging & Tamper-Proof Logs

### 11.1 Audit Log Schema

Every state-changing or administratively sensitive read action generates an immutable audit log.

```json
{
  "event_id": "uuid",
  "timestamp": "2026-04-01T10:00:00Z",
  "actor": { "id": "admin_uuid", "ip": "1.2.3.4", "role": "SUPPORT_LEAD" },
  "action": "ACCOUNT_FREEZE",
  "resource": { "type": "USER_ACCOUNT", "id": "target_uuid" },
  "state_before": { "status": "ACTIVE" },
  "state_after": { "status": "FROZEN" },
  "context": { "trace_id": "xtrace_uuid", "reason": "suspected_fraud_ticket_123" }
}
```

### 11.2 Tamper-Proofing (Hash Chain)

To prevent an insider from altering logs:
- Successive logs in the financial ledger and security audit log incorporate the hash of the preceding record (`current_hash = SHA256(prev_hash + current_data)`).
- Logs are streamed via Kafka to an S3 bucket configured with **Object Lock (WORM - Write Once, Read Many)** in Compliance mode (retention 10 years). Modifications or deletions are cryptographically impossible even for the AWS root user.
- **Cross-Account Storage**: Audit logs are piped to a dedicated, detached AWS Account operated exclusively by the Compliance team.

## 12. Fraud & Risk Controls

The Fraud & Risk service runs concurrently with transaction processing to detect and prevent malicious activity.

### 12.1 Transaction Risk Evaluation

| Control | Description | Action |
|---------|-------------|--------|
| **Velocity Checks** | > 3 high-value transactions within 5 minutes. | Trigger step-up auth. |
| **Transaction Limits** | Static per-tier (e.g., Tier 1 max 50M VND/day). | Reject gracefully. |
| **Device Fingerprinting**| Same device used for 5+ different accounts. | Auto-freeze accounts. |
| **Geo-Anomaly** | Login from Vietnam, transaction from EU within 1h. | Reject + trigger alert. |
| **Known Bad Actors** | IP/User matched against local or global sanction list. | Silent block + SAR filed. |

### 12.2 Risk Scoring & ML Integration

Every `PaymentIntent` is evaluated by a Risk Scoring Engine:
- **Score 0-30 (Low)**: Proceed normally.
- **Score 31-70 (Medium)**: Require OTP/MFA step-up.
- **Score 71-100 (High)**: Block transaction, manually review.

---

## 13. Monitoring & SIEM

All logs (audit, application, WAF, infrastructure) are aggregated into a Security Information and Event Management (SIEM) system (e.g., Datadog Cloud SIEM, Splunk, Elastic Security).

### 13.1 Example Security Alerts (P1 / Critical)

1. **Privilege Escalation Attempt**: Non-Admin user attempting `PUT /admin/*` endpoints > 5 times.
2. **Impossible Travel**: Same `sub` accessing from two geographic regions that are physically impossible to travel between in the time elapsed.
3. **Mass Data Export**: Spike in reads to `GET /users` from any internal IP.
4. **Key Compromise Indicator**: Unauthorized access denied by AWS KMS.
5. **Breach of Trust**: Service claiming to be `fraud-service` via JWT, but missing mTLS SPIFFE ID, or vice versa.

---

## 14. Incident Response

Incident Response Runbooks are codified for Category 1 (Critical) incidents.

### 14.1 Key Compromise (KMS CMK or JWT Private Key leaked)
- **Detection**: Found by GitHub secret scanning, or proactive disclosure.
- **Containment**: Execute "Break-Glass" KMS rotation script. Disables old CMK immediately. 
- **Recovery**: All user sessions are instantly invalid. Users forced to physically re-login. Re-issue all DEKs for PCI records within 48h.

### 14.2 Database Leak / Exfiltration
- **Detection**: SIEM alerts on mass EBS snapshot copy, irregular AWS CLI usage from production VPC, or mass `SELECT` statements.
- **Containment**: Network isolation of compromised instance. Revoke compromised IAM credentials. 
- **Recovery**: Since PII is field-level encrypted, impact is minimized to ciphertext. For plaintext data (logs), enforce customer notification protocol under PDPA/GDPR within 72h.

### 14.3 Fraud Attack (Coordinated Card Testing/Sweeping)
- **Detection**: Spike in payment declines (>400% baseline).
- **Containment**: WAF rate-limits applied to attacking ASN/IP range. Fraud module automatically drops risk threshold from 70 to 40, requiring step-up for all generic traffic.
- **Recovery**: Identify and manually rollback illicit ledger entries (or execute compensations).

---

## 15. Compliance Considerations

The platform is designed to maintain compliance under multiple regulatory frameworks.

### 15.1 PCI DSS (Relevant Concepts)
- **Network Segmentation**: The `PCI Zone` isolated conceptually out of the standard Application network.
- **Encryption**: TLS 1.3 only, AES-256 for all stored PAN data (if handled directly in future phases).
- **Access Control**: Strict RBAC, zero default access.

### 15.2 General / PDPA / GDPR
- **Right to Access / Erasure**: Handled asynchronously via Identity Service.
- **Data Minimization**: Blind indexing prevents querying arbitrary unhashed datasets.
- **Audit Trails**: Retained in immutable storage for 10 years (aligned with SBV requirements). 
- **Separation of Duties (SoD)**: Codified in maker-checker routines for system administration.

---

## 16. Security Checklist for New Services

Before any microservice is promoted to Production, the owning team must check off:

- [ ] **AuthN**: Middleware configured to validate JWT signature and expiration.
- [ ] **AuthZ**: RBAC/ABAC policies defined and applied to all endpoints. Explicitly rejects missing roles.
- [ ] **mTLS**: Added to the Istio mesh boundary with a valid SPIFFE ID.
- [ ] **Secrets**: Vault integrated. No env-var passwords.
- [ ] **Database**: Uses Prisma ORM parameterization. Has unique DB credentials.
- [ ] **Data Privacy**: No PII logged. PII columns are encrypted.
- [ ] **Validation**: Zod schema bounds testing applied to all inputs.
- [ ] **Rate Limiting**: Added to Gateway config for the new routes.
- [ ] **Dependencies**: Dependabot/Snyk passing with 0 High/Critical vulns.
- [ ] **Audit Logs**: All state-modifications emit standard structured audit events.

---

## 17. Environment Isolation & Engineer Access Security

A Zero Trust environment demands strict partitioning not just for microservices, but for the human engineers operating them.

### 17.1 Environment Isolation
- **Physical/Account Separation**: Development, Staging, and Production environments reside in entirely separate AWS/GCP accounts. 
- **No Shared Trust**: IAM roles, KMS keys, and network routes (VPC peering) never cross the boundary between Production and Non-Production.
- **Data Obfuscation**: Production data is never restored into Staging/Dev unless it traverses a robust PII sanitization / masking pipeline. 

### 17.2 Engineer Access Security (SSO, IAM, Bastion)
- **Identity Provider**: All engineer access is brokered through corporate SSO (Okta/Entra ID) enforcing hardware MFA (YubiKey) and conditional access (Corporate device, corporate IP).
- **Control Plane (AWS Console/API)**: Managed via AWS IAM Identity Center. Engineers assume short-lived, least-privilege roles specific to their domain. Read-Only by default; `AdministratorAccess` is explicitly banned.
- **Data Plane (Database/Server Access)**: 
  - Direct internet access to databases/servers is physically impossible.
  - Engineers authenticate to a **Bastion Host / Access Proxy** (e.g., HashiCorp Boundary, Teleport).
  - Proxy dynamically injects ephemeral credentials, eliminating standing access.
  - All proxy sessions (SSH/DB console) are **video recorded** and command-logged for forensic integrity.
- **Break-Glass Operations**: Emergency write access triggers an automated Page / Alert to the Security Operations Center (SOC) and requires dual approval.

---

## 18. Data Classification Framework

All data within the platform is classified to dictate its handling, encryption, and retention requirements.

| Level | Classification | Examples | Encryption required (At-Rest) | Encryption required (In-Transit) |
|-------|----------------|----------|-------------------------------|----------------------------------|
| **L4** | **Restricted / Secret** | Private Keys, KMS Material, Bank API Secrets | FIPS 140-2 HSM / Envelope Encrypted | TLS 1.3 |
| **L3** | **Confidential / PII** | Wallet Balances, National IDs, Phone Numbers | AES-256-GCM (Field-level encryption) | TLS 1.3 / mTLS |
| **L2** | **Internal** | Fraud rules, Source code, Aggregated financial reports | Volume-level (TDE) | TLS 1.2+ |
| **L1** | **Public** | Marketing assets, Published API documentation | Standard S3/EBS encryption | TLS 1.2+ |

*Note: The platform is engineered such that L3/L4 data is never emitted into Application or Access logs.*

---

## 19. Backup & Disaster Recovery Security

Disaster Recovery (DR) operations can often be a backdoor for data exfiltration if not secured identically to Production.

### 19.1 Backup Security
- **Encryption**: All snapshots (RDS, ElastiCache) and archived logs (S3) are encrypted using AWS KMS. The KMS key for backups is tightly constrained by IAM resource policies.
- **Immutability (Ransomware Protection)**: Backups are stored in an S3 Vault configured with AWS Backup Vault Lock (Write-Once-Read-Many) ensuring that even compromised root accounts cannot delete recovery points before their retention expiry.
- **Air-Gapped Vault**: Critical L3/L4 database backups are replicated daily to a cold, disconnected "vault" AWS account with strictly separated IAM governance.

### 19.2 Disaster Recovery Execution
- **Automated Restore**: DR redeployment is entirely driven by Infrastructure as Code (IaC). Manual console interventions are restricted.
- **Synchronized Key Material**: KMS CMKs used for field-level encryption are replicated as Multi-Region Keys to ensure data remains decryptable post-failover without requiring the transport of raw key material across geographic bounds.

---

## 20. Insider Threat Protection

To combat threats originating from authenticated, trusted employees, the platform implements:
- **Separation of Duties (Maker-Checker)**: Discussed in §5.4. No single entity can propose and approve a sweeping configuration change or massive fund transfer.
- **Mandatory Vacation / Rotation**: High-privilege operators (Treasury, Platform Admins) must take complete detachment blocks. Access is suspended during these blocks to surface concealed operational anomalies.
- **SIEM Behavioral Anomaly Guardrails**: Datadog/Splunk actively monitors for engineers downloading unusual quantities of records (Database Dump alerts) or interacting with production DBs outside of corresponding Jira Incident tickets.

---

## 21. Data Exfiltration Prevention Strategy

Protecting sensitive financial data from leaving the platform unauthorized requires structural barriers beyond mere access-controls.

### 21.1 Core Exfiltration Protections
- **Zero-Egress Data Networks**: As defined in §8, the `Data Zone` running PostgreSQL/Kafka has absolutely no routing to the public Internet, not even via NAT. A compromised database node physically cannot pipe data to an external S3 bucket or cURL an attacker address.
- **DLP on Edge Proxies**: Cloudflare/AWS WAF scans outbound HTTP responses for raw PANs (Primary Account Numbers) or PII patterns that have slipped past encryption thresholds, breaking the connection implicitly.
- **Endpoint Protection (Engineers)**: Employee laptops are managed via MDM preventing mass USB storage mounting and forcing Zscaler/Cloudflare Zero Trust for outbound data transfers connecting onto the Bastions.
- **VPC Endpoint Restriction**: AWS PrivateLink connections to our S3 buckets use Endpoint Policies that explicitly deny `s3:PutObject` requests targeting external, non-corporate AWS Account buckets. This blocks an attacker from dumping our DB into their personal S3 account from our VPC.

---

## 22. ADRs (Architecture Decision Records)

To be linked to specific Architecture Decision Records established later in the Software Design phases.

## 23. KPIs & Exit Criteria

*Refer back to original phase checklist.*
