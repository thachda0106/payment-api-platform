# Payment Platform — Knowledge Prerequisites

> A structured learning series covering all domain fundamentals you must understand **before** writing a single line of code for this Payment API Platform.

---

## Why This Series Exists

You have strong skills in NestJS, TypeScript, AWS, Terraform, Docker, and microservices. These are exactly the right tools to build this platform. However, **payment systems are not typical CRUD applications**. The domain itself is the hardest part — financial correctness, regulatory compliance, fraud prevention, and distributed consistency are non-negotiable.

This series bridges the gap between "I can build distributed systems" and "I can build a payment platform."

---

## Prerequisite Self-Assessment

| Knowledge Area | If You... | Start At |
|---------------|-----------|----------|
| Payment lifecycle (auth, capture, settlement) | Don't know these terms | Module 01 |
| Double-entry accounting | Think debit = negative, credit = positive | Module 02 |
| PCI DSS / KYC / AML | Don't know what these acronyms mean | Module 03 |
| Idempotency keys, saga patterns | Haven't implemented these in prod | Module 04 |
| Fraud detection / risk scoring | Never built or integrated one | Module 05 |
| Settlement files / bank reconciliation | Never heard of NOSTRO/VOSTRO | Module 06 |

---

## Learning Modules

| # | Module | Duration | Critical? |
|---|--------|----------|-----------|
| 01 | [Payment Domain Fundamentals](01-payment-domain-fundamentals.md) | 2–3 hours | Yes |
| 02 | [Financial Accounting for Engineers](02-financial-accounting-for-engineers.md) | 3–4 hours | Yes |
| 03 | [Payment Security & Compliance](03-payment-security-and-compliance.md) | 2–3 hours | Yes |
| 04 | [Idempotency & Distributed Consistency](04-idempotency-and-consistency.md) | 3–4 hours | Yes |
| 05 | [Fraud Detection Fundamentals](05-fraud-detection-basics.md) | 2–3 hours | Recommended |
| 06 | [Settlement & Reconciliation](06-settlement-and-reconciliation.md) | 3–4 hours | Yes |
| 07 | [Go Language & Ecosystem for Payment Systems](07-go-language-and-ecosystem.md) | 4–5 hours | Yes |
| 08 | [Observability Stack](08-observability-stack.md) | 3–4 hours | Yes |

**Total estimated study time**: 22–30 hours

---

## Study Sequence

```
Module 01 ──▶ Module 02 ──▶ Module 03
                                │
                                ▼
Module 04 ◀─────────────────────┘
    │
    ├──▶ Module 05
    │       │
    │       ▼
    │    Module 07 ──▶ Module 08
    │
    └──▶ Module 06
```

---

## How Each Module Works

1. **Read the module** — understand the concepts, not just memorize
2. **Answer the Check Questions** at the end — if you can't, re-read
3. **Relate to the Project** — each module maps to specific phases in the 30-phase lifecycle
4. **Build the Mini-Exercise** (where provided) — hands-on practice

---

## Mapping to Project Phases

| Module | Relevant Project Phase(s) |
|--------|---------------------------|
| 01 — Domain Fundamentals | Phase 01 (Product Discovery), Phase 10 (System Flows) |
| 02 — Financial Accounting | Phase 04 (Domain Design), Phase 07 (Data Architecture) |
| 03 — Security & Compliance | Phase 05 (Security Architecture), Phase 22 (Compliance Audit) |
| 04 — Idempotency & Consistency | Phase 06 (High-Level Architecture), Phase 08 (API Design) |
| 05 — Fraud Detection | Phase 03 (Risk Analysis), Phase 04 (Domain Design) |
| 06 — Settlement & Reconciliation | Phase 04 (Domain Design), Phase 10 (System Flows) |
| 07 — Go Language & Ecosystem | Phase 11 (Technology Selection), Phase 17 (Vertical Slice) |
| 08 — Observability Stack | Phase 20 (Observability), Phase 06 (Architecture) |

---

## Quick-Reference Glossary

| Term | Definition |
|------|-----------|
| **Acquirer** | Bank/financial institution that processes payments on behalf of a merchant |
| **Issuer** | Bank that issued the card/wallet to the customer |
| **Payment Gateway** | Technology that routes payment authorization requests |
| **Settlement** | Actual movement of funds between banks (happens after capture) |
| **Chargeback** | Forced reversal of a transaction initiated by the cardholder's bank |
| **PCI DSS** | Payment Card Industry Data Security Standard |
| **KYC** | Know Your Customer — identity verification regulation |
| **AML** | Anti-Money Laundering — financial crime prevention |
| **Ledger** | Immutable record of all financial entries |
| **Journal Entry** | A single debit+credit pair in the ledger |
| **Reconciliation** | Process of matching internal records against external (bank) records |
| **Idempotency** | Property where performing an operation multiple times has the same effect as once |
| **Saga** | Pattern for maintaining data consistency across services using compensating transactions |
| **NOSTRO** | "Our money at your bank" — the bank's view of an account held at another bank |
| **VOSTRO** | "Your money at our bank" — the other bank's mirror view of the NOSTRO account |

---

> **Start with Module 01.** Everything builds from payment fundamentals.
