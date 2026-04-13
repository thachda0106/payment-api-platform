# Phase 08 — API Design

## MoMo-like Payment API Platform

> **Document Status**: Draft v1.0
> **Last Updated**: 2026-04-13
> **Classification**: CONFIDENTIAL — Internal Use Only
> **Audience**: Backend Engineers, Frontend Engineers, Mobile Integrators
> **Input**: Phase 07 — Data Architecture
> **Approval Gate**: 🏗️ Architecture Review Board (ARB) Final Sign-off

---

## 1. Goal
To establish a definitive, "Contract-First" API Design aligning perfectly with the Phase 07 Zero-Trust Database Architecture. This phase dictates the RESTful endpoints, payload structures, strict Idempotency enforcement, and deterministic error handling mirroring Stripe's Tier-1 integration standards.

## 2. Key Decisions

- **Strict REST Semantics**: APIs utilize standard HTTP verbs (`GET`, `POST`, `PATCH`, `DELETE`) mapping cleanly to underlying resources. RPC-style paths (`/executePayment`) are restricted.
- **Mandatory Idempotency Headers**: Every state-mutating request (`POST`, `PATCH`, `DELETE`) must contain a unique `Idempotency-Key` header. Missing keys yield immediate `400 Bad Request`.
- **API Versioning Parameterization**: Versioning is done fundamentally via the URI route `v1` combined with header-scoped semantic dates `Stripe-Version: 2026-04-13` to enable backend schema evolution without breaking integrations.
- **Deterministic 4XX Error Definitions**: Direct mirroring of DB-level mathematical limits to API outputs. (e.g., A DB "Insufficient Funds" sequence exception automatically maps to a strict `422 Unprocessable Entity - code: insufficient_funds`).
- **Pagination Standard**: Utilizes cursor-based pagination (using sequences) instead of offset-based pagination to guarantee consistent list pulling under heavy transaction inserts.

## 3. Documents Produced
- The REST API Reference Design (This document).
- (Pending) the full `openapi.yaml` configuration stored in `docs/cross-cutting/api/specs/`.

## 4. Architecture Artifacts

### 4.1 Endpoint Matrix
| Action | Method | Path | Auth Scope | Idempotency |
| :--- | :--- | :--- | :--- | :--- |
| **Initiate Payment** | `POST` | `/v1/payments` | `write:payments` | **Required** |
| **Get Payment** | `GET` | `/v1/payments/{id}` | `read:payments` | N/A |
| **Execute Refund** | `POST` | `/v1/payments/{id}/refunds`| `write:refunds` | **Required** |
| **Check Wallet Balance**| `GET` | `/v1/wallets/balances` | `read:wallets` | N/A |
| **Transaction History** | `GET` | `/v1/transactions` | `read:transactions` | N/A |

### 4.2 Error Status Mapping
| Standard Check | HTTP Code | Structural Mapping |
| :--- | :--- | :--- |
| **Missing Idempotency-Key** | `400 Bad Request` | Edge-node Gateway interception |
| **Double Execution detected**| `409 Conflict` | Idempotency row collision status conflict |
| **Negative Balance Guard** | `422 Unprocessable Entity`| DB Trigger exception `Insufficient Funds` |
| **Missing Account / Typology**| `404 Not Found` | DB Account resolution failure |

## 5. Example Deliverables

### 5.1 POST /v1/payments
Initiates a dual-entry transfer securely mapping data onto the DB Store Procedure.
**Request**:
```http
POST /v1/payments HTTP/1.1
Host: api.payments.platform.com
Authorization: Bearer sk_live_aBc123...
Idempotency-Key: cb174dc0-2ed2-4b2a-bf35-a131015fc65e
Content-Type: application/json

{
  "source_account_id": "acc_001_wallet",
  "destination_account_id": "acc_002_merchant",
  "amount": 15000,
  "currency": "VND",
  "description": "Coffee Purchase"
}
```

**Response (Success)**:
```json
{
  "id": "pay_xyz987654",
  "object": "payment",
  "amount": 15000,
  "currency": "VND",
  "status": "succeeded",
  "created": 1713024000,
  "description": "Coffee Purchase"
}
```

**Response (DB Typology Failure)**:
```json
{
  "error": {
    "type": "invalid_request_error",
    "code": "insufficient_funds",
    "message": "The source account lacks sufficient funds for this transaction."
  }
}
```

## 6. Key Questions
- Q: Do we expose the mathematical sequence and internal `hash_chain` validations outside to the API consumer?
  - A: No, internal cryptographic ledger logic is abstracted to maintain zero external dependencies. We export only standard timestamp references.

## 7. Implementation Tasks
- [ ] Define the primary JSON schemas representing the Request/Response boundaries mapped to the data tier.
- [ ] Document strict authentication boundaries utilizing API keys matching the Security Architecture definitions.
- [ ] Configure Postman / Insomnia collections for CI/CD staging verification.

## 8. Common Mistakes
- **Leaking DB Tracing Details**: Returning literal Postgres DB exception tracebacks instead of clean API DTO standardized error models.
- **Implicit Typology Matching**: Assuming the API doesn't need to specify `currency` and relying purely on account configurations.

## 9. KPIs & Exit Criteria
- [ ] 100% of modifying API endpoints are mapped explicitly with `Idempotency-Key` headers.
- [ ] The API data model aligns identically with the physical properties declared by Phase 07 Data Architecture.
- [ ] Formal OpenAPI swagger files validate mathematically against standard linters.

## 10. Connection to Next Phase
Following the contract definitions mapped here, **Phase 09 (Event Schema & Governance)** will construct the downstream asynchronous Kafka boundaries. The API essentially writes into the local DB, which then delegates logic flow formats seamlessly into Phase 09 Event arrays.
