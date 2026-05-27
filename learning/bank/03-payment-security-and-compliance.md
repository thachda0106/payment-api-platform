# Module 03 — Payment Security & Compliance

## Duration: 2–3 hours | Critical: Yes

---

## Learning Objectives

By the end of this module, you will understand:
- PCI DSS — what it is, what it requires, and how it affects architecture
- KYC (Know Your Customer) — identity verification requirements in Vietnam
- AML (Anti-Money Laundering) — transaction monitoring and reporting
- PSD2 / Open Banking — if expanding to Europe/SEA
- Data protection: encryption at rest, in transit, field-level encryption
- Tokenization vs. encryption for sensitive data
- Security architecture patterns for payment platforms

---

## 1. PCI DSS — Payment Card Industry Data Security Standard

### What It Is

PCI DSS is a set of 12 security requirements for any organization that handles cardholder data. It's managed by the PCI Security Standards Council (Visa, Mastercard, Amex, Discover, JCB).

### Who It Applies To

| Role | Example | PCI Scope |
|------|---------|-----------|
| **Merchant** | Accepts credit cards | Must certify (level varies by volume) |
| **Service Provider** | Processes/stores card data on behalf of merchants | Must certify as Level 1 |
| **Payment Gateway** | Routes transactions | SAQ D or Level 1 |
| **Payment Platform (Us)** | If we accept/process/store card data directly | Level 1 Service Provider |

### 12 PCI DSS Requirements Summary

| # | Requirement | Architecture Impact |
|---|-------------|---------------------|
| 1 | Install and maintain firewall | Network segmentation (CDE vs. non-CDE) |
| 2 | No vendor defaults | No default passwords, configurations |
| 3 | Protect stored card data | **Encryption + tokenization** — NEVER store PAN in plaintext |
| 4 | Encrypt cardholder data in transit | TLS 1.2+ everywhere |
| 5 | Anti-malware | Applies to POS/Windows systems, less to our Linux/K8s stack |
| 6 | Secure applications | Secure coding, SAST, dependency scanning |
| 7 | Restrict access by need-to-know | RBAC + ABAC — our Phase 05 design |
| 8 | Identify and authenticate | MFA for admin, strong passwords |
| 9 | Restrict physical access | Data center access controls |
| 10 | Track and monitor all access | Audit logs — immutable, cannot be modified |
| 11 | Test security regularly | Vulnerability scans, penetration testing |
| 12 | Security policy | Documented policies, incident response |

### Critical PCI Requirements for Architecture

**Requirement 3 — Protect Stored Card Data**:
- Never store full PAN after authorization (except for explicit business need)
- Render PAN unreadable: encryption, hashing, truncation, or **tokenization**
- **Tokenization** (our approach): Replace PAN with a token. Token has no mathematical relationship to PAN. Real PAN only lives in the vault.

**Requirement 10 — Track and Monitor**:
- All access to cardholder data must be logged
- Logs must be immutable (append-only)
- Logs must be available for 12 months (3 months online, rest archived)

### SAQ (Self-Assessment Questionnaire) Types

| SAQ | Who | What They Do |
|-----|-----|-------------|
| A | Card-not-present, no storage | Outsources all card processing |
| A-EP | E-commerce, outsource but have some control | Custom e-commerce with 3rd party payment |
| B | Imprint-only or standalone terminals | Physical retail with dial-up terminals |
| C-VT | Virtual terminals | Web-based "keyed-in" transactions |
| D | Everyone else | **The hardest one. Most merchants are SAQ D.** |

### PCI DSS 4.0 (Current version, effective March 2024)

Major changes from 3.2.1:

| Change | Impact |
|--------|--------|
| **Customized approach** | Can define compensating controls instead of following prescriptive requirements |
| **Continuous security** | Annual scan → continuous monitoring |
| **Updated multi-factor auth** | MFA for all administrative access to CDE (not just remote) |
| **Stronger encryption** | TLS 1.2 minimum, AES-256 recommended |

---

## 2. KYC — Know Your Customer

### Why It Exists

Regulators require financial service providers to verify the identity of their customers to prevent:
- Money laundering
- Terrorist financing
- Identity theft
- Fraud

### Vietnam-Specific KYC Requirements (Circular 23/2019, Circular 47/2024)

| Tier | Requirements | Monthly Transaction Limit |
|------|-------------|---------------------------|
| **Tier 1** | Phone number (mobile) | 10M VND |
| **Tier 2** | Phone + Full name + ID/Passport + Photo + Bank account link | 100M VND |
| **Tier 3** | Tier 2 + Biometric data (facial recognition) + eKYC | Full (no limit) |

### eKYC Flow (Electronic KYC)

```
┌──────────┐  1. Submit ID photo    ┌──────────────┐
│  User    │  + Selfie + NFC chip   │  Platform    │
│  Phone   │────────────────────────▶│  Backend     │
└──────────┘                        └──────┬───────┘
                                            │
              ┌─────────────────────────────┤
              ▼                             ▼
      ┌──────────────┐            ┌──────────────────────┐
      │ ID OCR       │            │ Liveness Detection   │
      │ (extract info)│           │ (blink, turn head)   │
      └──────┬───────┘            └──────────┬───────────┘
             ▼                               ▼
      ┌──────────────┐            ┌──────────────────────┐
      │ NFC Chip Read│            │ Face Match           │
      │ (scraped ID) │            │ (selfie vs ID photo) │
      └──────────────┘            └──────────────────────┘
```

### Sanctions Screening

All new customers must be screened against:
- **OFAC** (US Office of Foreign Assets Control)
- **UN Sanctions List**
- **Vietnam SBV Blacklist**
- **PEP Lists** (Politically Exposed Persons)

If a match score exceeds threshold (~80%), a manual review is triggered.

---

## 3. AML — Anti-Money Laundering

### Three Stages of Money Laundering

```
1. PLACEMENT     → 2. LAYERING       → 3. INTEGRATION
Introduce illicit   Obscure trail via   Funds re-enter
funds into          multiple             legitimate
financial system    transactions         economy
```

### Transaction Monitoring Rules

Our platform must implement:

| Rule | Description | Action |
|------|-------------|--------|
| **Velocity Check** | > 10 transactions in 1 hour to same beneficiary | Flag + CAPTCHA |
| **Volume Threshold** | Single transaction > 500M VND | Enhanced Due Diligence |
| **Daily Aggregation** | Total daily outflow > 1B VND | Temporary freeze + manual review |
| **Structuring Detection** | Multiple transactions just below 10M VND (reporting threshold) | Flag + SAR filing |
| **Geographic Anomaly** | Login from country X, transaction to country Y (unusual pair) | Step-up auth |
| **New Account Rush** | New account, immediately large transaction | Hold 24h |
| **Round Dollar** | Amounts like 9,999,000 VND (just below threshold) | Flag for review |

### SAR (Suspicious Activity Report)

If any rule triggers and cannot be dismissed automatically, a SAR must be filed with SBV within:
- **Vietnam**: 72 hours (urgent) or 30 days (standard)
- **Global**: Varies by jurisdiction (typically 30 days)

### AML Compliance Program Requirements

1. **Appoint AML Compliance Officer**
2. **Risk-based customer due diligence** (CDD/EDD)
3. **Transaction monitoring system** (us!)
4. **Record keeping**: 5 years minimum
5. **Internal controls**: Policies, procedures, training

---

## 4. Encryption Strategy

### 4.1 Envelope Encryption (AWS KMS)

```
┌──────────┐     Generate DEK      ┌────────────┐
│ Application│◀────────────────────▶│ AWS KMS    │
│ (Service)  │  Encrypt DEK with KEK│ (Key       │
└─────┬──────┘  (returns encrypted  │ Management)│
      │         DEK + plaintext DEK)└────────────┘
      │
      │ Plaintext DEK (in memory only, ~1ms TTL)
      ▼
┌──────────────────────────────────────────────┐
│ Use DEK to encrypt/decrypt field values      │
│ (AES-256-GCM)                                │
│                                              │
│ Store: encrypted_base64 + encrypted_key + IV │
│ Discard: plaintext DEK after operation       │
└──────────────────────────────────────────────┘
```

### 4.2 Field-Level Encryption (PII Protection)

```sql
-- PII columns are encrypted
CREATE TABLE users (
    id              UUID PRIMARY KEY,
    phone_number    TEXT,    -- Encrypted with AES-256-GCM
    full_name       TEXT,    -- Encrypted
    email           TEXT,    -- Encrypted
    id_number       TEXT,    -- Encrypted (encrypted at application layer)
    blind_index     TEXT,    -- HMAC-SHA256(phone) for exact lookups
    kyc_tier        INT,
    created_at      TIMESTAMPTZ
);
```

### 4.3 Blind Index Pattern

To allow exact-match queries on encrypted data (without decrypting):

```go
func GenerateBlindIndex(phone string) string {
    // HMAC-SHA256 with a dedicated key
    hmac := hmac.New(sha256.New, []byte(blindIndexKey))
    hmac.Write([]byte(phone))
    return base64.StdEncoding.EncodeToString(hmac.Sum(nil))
}

// Usage:
// Phone is encrypted. But we can query: WHERE blind_index = ?
// For SQL: WHERE blind_index = 'generated_hmac'
```

### 4.4 Tokenization for Card Data

If we process cards, we use tokenization:

```
PAN: 4111 1111 1111 1111  →  Token: tok_live_8f7a3b2c1d0e

Token is:
- Random (no mathematical relationship to PAN)
- Same length or variable (doesn't reveal card type)
- Replaceable (if compromised, issue new token)
- Vault-stored (token → PAN mapping in encrypted vault)
```

---

## 5. Security Architecture Patterns (From Phase 05)

### Network Segmentation

```
┌─────────────────────────────────────────────┐
│                PUBLIC INTERNET               │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│          PUBLIC SUBNET (DMZ)                  │
│  Kong API Gateway · WAF · External ALB        │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│          APPLICATION SUBNET                   │
│  Wallet · Payment · Merchant · Ledger         │
│  (Istio mTLS between services)               │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│           DATA SUBNET                         │
│  Aurora PostgreSQL · ElastiCache · MSK        │
│  (No public access — VPC only)               │
└─────────────────────────────────────────────┘

PCI CDE (Cardholder Data Environment):
┌─────────────────────────────────────────────┐
│           CDE SUBNET                          │
│  Token Vault · KMS · HSM                     │
│  (Strictly segmented from non-CDE)           │
└─────────────────────────────────────────────┘
```

### Authentication Matrix

| Access Type | Method | MFA? |
|-------------|--------|:----:|
| User → App (login) | JWT (RS256) + OTP | Optional |
| User → App (payment) | JWT + PIN/Biometric | Yes |
| Service → Service | mTLS (SPIFFE) + internal JWT | N/A |
| Admin → Dashboard | JWT + TOTP | Yes |
| API → Gateway | API Key + HMAC Signature | N/A |

---

## Check Questions

1. What is PCI DSS and why does it affect our architecture?
2. What are the 3 KYC tiers in Vietnam?
3. What is a blind index and why is it needed?
4. What's the difference between encryption and tokenization?
5. What triggers an AML alert for "structuring"?
6. What's a SAR and how long does Vietnam give you to file?
7. Why must the Cardholder Data Environment (CDE) be network-segmented?
8. How does envelope encryption work with AWS KMS?

---

## Next Module

[Module 04 — Idempotency & Distributed Consistency](04-idempotency-and-consistency.md)

> The hardest bugs in payment systems aren't logic bugs — they're the silent ones where money is created or destroyed.
