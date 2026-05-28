# Module 02 — RBAC, Secrets, PCI DSS & Threat Modeling

## 2.1 RBAC (Role-Based Access Control)

### Payment Platform Roles

| Role | Permissions |
|------|------------|
| **USER** | Read own wallet, create payments, view own transactions |
| **MERCHANT** | View settlements, manage webhooks, issue refunds |
| **ADMIN** | Freeze accounts, adjust balances, view all data |
| **AUDITOR** | Read-only access to audit logs (no mutations) |

### Spring Security Implementation

```java
@PreAuthorize("hasAuthority('SCOPE_write:payments')")
public PaymentResult createPayment(PaymentRequest req) { ... }

@PreAuthorize("hasRole('ADMIN')")
public void freezeAccount(String accountId) { ... }

// Custom permission evaluator for resource-level access
@PreAuthorize("hasPermission(#paymentId, 'Payment', 'read')")
public Payment getPayment(String paymentId) { ... }
```

## 2.2 Secrets Management

### HashiCorp Vault

```
Application → Vault API (authenticate via K8s Service Account)
  → Vault generates temporary DB credentials (TTL: 1 hour)
  → Application uses credentials, Vault auto-revokes after TTL
```

**Dynamic secrets**: No static credentials stored anywhere. Credentials exist only for their TTL. If stolen, they expire automatically.

### AWS Secrets Manager + KMS

```
Secrets Manager stores: DB password, API keys (encrypted with KMS)
Application → Secrets Manager SDK → retrieve secret (decrypted by KMS)
Rotation: Secrets Manager auto-rotates every 30 days (via Lambda)
```

### Secret Detection in CI/CD

```bash
# Never commit secrets to Git!
# Use tools to detect leaks:
git-secrets --scan
trufflehog git file://. --since-commit HEAD
```

## 2.3 PCI DSS (Key Requirements Mapped to Platform)

| # | Requirement | Platform Implementation |
|---|-------------|------------------------|
| 3 | Protect stored cardholder data | Tokenize PANs (store only last 4 digits). Encrypt PII at rest with AES-256-GCM via KMS envelope encryption. |
| 4 | Encrypt transmission | TLS 1.3 for all external APIs. mTLS (Istio) for internal services. |
| 7 | Restrict access by need-to-know | RBAC: `@PreAuthorize` per endpoint. Database: separate schemas per role. |
| 8 | Identify and authenticate | OAuth2 + JWT (RS256). MFA for admin access. API keys for merchant integrations. |
| 10 | Track and monitor access | Immutable audit trail (hash-chained). Every action logged: actor, resource, action, result. 7-year retention. |
| 11 | Regularly test security | SAST (static analysis) in CI. DAST (OWASP ZAP) in staging. Container scanning (Trivy). Dependency scanning (Snyk/npm audit). |

## 2.4 Threat Modeling (STRIDE)

| Threat | Payment Example | Mitigation |
|--------|----------------|-----------|
| **Spoofing** | Attacker uses stolen API key | JWT with short expiration, key rotation |
| **Tampering** | Modify payment amount in transit | TLS, request signing (HMAC) |
| **Repudiation** | User claims "I didn't make this payment" | Immutable audit trail, idempotency tracking |
| **Information Disclosure** | Log output contains full PAN | PII masking in logs, field-level encryption |
| **Denial of Service** | 100K requests/second | Rate limiting (Redis token bucket), WAF, AWS Shield |
| **Elevation of Privilege** | Merchant API key accesses admin endpoints | RBAC validation per endpoint, scope checking |

## 2.5 OWASP Top 10 (Payment-Specific)

| # | Vulnerability | Payment Example | Prevention |
|---|-------------|----------------|-----------|
| A01 | Broken Access Control | GET /v1/payments/{id} returns other users' payments | Verify payment.userId == authenticated user |
| A02 | Cryptographic Failures | PANs stored in plaintext | Tokenization, AES-256-GCM encryption |
| A03 | Injection | SQL injection in payment search | Parameterized queries / JPA |
| A04 | Insecure Design | No rate limiting on payment endpoint | Token bucket per user |
| A05 | Security Misconfiguration | DEBUG endpoints exposed in production | Disable via Spring profiles |
| A06 | Vulnerable Components | Old Jackson version with CVE | Dependabot/Snyk in CI |
| A07 | Auth Failures | Weak JWT signing (HS256 with guessable secret) | RS256/ES256 with KMS |
| A08 | Software & Data Integrity | Malicious dependency in supply chain | Lock files, checksums, SBOM |
| A09 | Logging & Monitoring Failures | No audit log for payment mutations | Immutable append-only audit table |
| A10 | SSRF | Service fetches arbitrary URLs (webhook testing) | URL allowlist |

## 2.6 Exercises

### Ex 2.1 — RBAC Implementation
Define 3 roles (USER, MERCHANT, ADMIN). Implement Spring Security with method-level `@PreAuthorize`. Test: USER can read own payments, cannot read others'. ADMIN can do everything.

### Ex 2.2 — Threat Model
Draw a data flow diagram for the payment API. Apply STRIDE to each element. Identify 5 threats. For each, propose a mitigation. Write as a threat model document.

### Ex 2.3 — PCI DSS Mapping
Take the PCI DSS 12 requirements. For each, identify: (a) how the payment platform satisfies it, (b) what evidence you would provide in an audit, (c) any gaps that need to be addressed.

## 2.7 Self-Assessment

- [ ] Can design an RBAC system with fine-grained resource-level permissions
- [ ] Understand dynamic secrets vs static secrets and when to use Vault
- [ ] Can map PCI DSS requirements to specific platform implementations
- [ ] Can apply STRIDE to a system and produce a threat model
- [ ] Know the OWASP Top 10 and can identify each in a payment context
