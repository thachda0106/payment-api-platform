# Security Architecture — Cross-Cutting Reference

## MoMo-like Payment API Platform

> **Source**: Phase 05 — Security Architecture  
> **Purpose**: Quick-reference for all teams implementing security controls  
> **Full details**: [05-security-architecture.md](../../stages/B-domain-architecture/05-security-architecture.md)
> **Classification**: Internal Use Only

---

## 1. Authentication & API Security Quick Reference

| Aspect | Implementation Standard |
|--------|-------------------------|
| **Signing algorithm** | RS256 (KMS-managed HSM) |
| **Access token lifetime** | 15 minutes |
| **Refresh token lifetime** | 7 days (with single-use rotation) |
| **Password/PIN hashing** | Argon2id (64MB memory, 3 iterations, 4 parallelism) |
| **OTP Generation** | 6-digit CSPRNG, 5-min expiry, max 3 attempts |
| **Service-to-service Auth** | mTLS (SPIFFE ID) + internal service JWT |
| **Token revocation** | Redis SET (blacklist), TTL = remaining token lifetime |
| **Idempotency** | Required for all `POST`/`PUT`/`PATCH` via `X-Idempotency-Key` |
| **API Rate Limits** | Public IP: 5 req/sec. Auth User: 20 req/sec |

## 2. RBAC & ABAC Quick Reference

| Role | Key Permissions | ABAC Restrictions |
|------|----------------|-------------------|
| `USER` | Own wallet operations only | Subject to `Transaction Limits` (KYC Tier) |
| `MERCHANT` | Own merchant account | Subject to `Refund Maximums` |
| `SUPPORT_AGENT` | Read-only: users, transactions | Time-of-day access, geo-fenced |
| `SUPPORT_LEAD` | Above + freeze/unfreeze | Maker role for large refunds |
| `FINANCE_ADMIN` | Ledger, settlements, refunds | Checker role. Step-up auth required > 50M |
| `RISK_ANALYST` | Fraud rules, SAR filing | Maker role for rule adjustment |
| `PLATFORM_ADMIN` | Feature flags, core configs | VPC/VPN network restriction |
| `SUPER_ADMIN` | Full permissions | Hard-token MFA + Dual Control mandatory |

## 3. Encryption & Data Quick Reference

| Category | Standard | Notes |
|----------|----------|-------|
| **Data At Rest** | AES-256-GCM (Envelope Encryption) | Via AWS KMS. All PostgreSQL and Redis volumes. |
| **PII Data** | Field-level Encryption | App encrypts explicitly via SDK before DB insertion. |
| **PII Search** | HMAC-SHA256 Blind Index | Allows exact match without decryption. |
| **Data In Transit** | TLS 1.3 | Public internet (ALB) and intra-service (mTLS). |
| **Secrets Management** | AWS Secrets Manager / Vault | No env-vars. Pods fetch dynamically. Rotated <90d. |

## 4. Network Zones & Boundaries

| Zone | CIDR | Purpose | Restrictions |
|------|------|---------|--------------|
| **Public** | `10.0.1.0/24` | WAF, ALB, API Gateway | Inbound strictly 443. |
| **Application**| `10.0.10.0/24` | All microservices (EKS/ECS) | Egress via NAT. Ingress from ALB only. |
| **Data** | `10.0.20.0/24` | PostgreSQL, Redis, Kafka | **No outbound internet.** Ingress from App only. |
| **PCI / Core**| `10.0.30.0/24` | Bank Integration, Ledger | **Strict isolation.** IP-whitelisted egress. |

## 5. Security Checklist for New Services

*Every service promotes to Prod must affirmatively verify:*

- [ ] **AuthN Verification**: JWT signature and expiration middleware is properly configured.
- [ ] **AuthZ Enforcement**: RBAC/ABAC guards explicitly decorate every endpoint.
- [ ] **Input Validation**: `Zod` schema validation rejecting unknown fields (`strict()` mode).
- [ ] **mTLS Integration**: Service sidecar config is active, validating caller SPIFFE IDs.
- [ ] **Secrets Hygiene**: No secrets committed to git. Database passwords fetch at runtime.
- [ ] **ORM Safety**: Raw SQL avoided. All DB queries parameterized via Prisma.
- [ ] **PII Protection**: Sensitive columns are tagged for envelope encryption. PII is scrubbed from logs.
- [ ] **Audit Completeness**: All state-modifying endpoints trigger an immutable audit event via Kafka.
- [ ] **Vulnerability Check**: SAST and Dep-Shield build steps report 0 High or Critical vulnerabilities.
- [ ] **Idempotency Check**: Payment intents or ledger mutations enforce Idempotency keys gracefully.

## 6. Incident Response Quick-Dial

- **Key/Token Compromise**: Execute `KMS Break-Glass Protocol` internally.
- **Fraud Sweeping/Volumetric**: Lower WAF Rate Limit threshold explicitly for targeting ASN.
- **Data Exfiltration**: Freeze targeted Data subnet VPC route, trigger CloudTrail analysis.

## 7. ADR Index (Security)

| ADR | Title | Status |
|-----|-------|--------|
| ADR-006 | JWT Signing with RS256 + KMS | Accepted |
| ADR-007 | Argon2id for PIN Hashing | Accepted |
| ADR-008 | RBAC with CASL Library | Accepted |
| ADR-009 | ABAC & Maker-Checker Workflows | Accepted |

## 8. Engineer Access & Environment Quick Reference

| Control | Mechanism |
|---------|-----------|
| **Prod Access Default** | Explicitly deny. Blocked at IAM/VPC level. |
| **Emergency DB Access** | Bound through Bastion Host (Teleport/Boundary) only. |
| **Access Verification** | SSO + Hardware MFA + Just-in-Time Ephemeral Credentials. |
| **Environment Promotes** | Prod, Staging, Dev are completely physically separate AWS Accounts. |
| **Audit Logs** | Bastion proxy records video/keystrokes of session. |

## 9. Data Classification Quick Reference

| Level | Description | Handling Rule |
|-------|-------------|---------------|
| **L4 (Restricted)** | KMS Material, Private Keys | Envelope encrypted. No human access. |
| **L3 (Confidential)** | PII (National ID), Balances | Field-level encryption. Replicate securely. Scrubbed from logs. |
| **L2 (Internal)** | Source Code, Fraud Rules | TDE/Volume-level encryption. |
| **L1 (Public)** | API Docs, Open Specs | Standard TLS transmission. |
