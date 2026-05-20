# Phase 08 — API Design (Contract-First)

## MoMo-like Payment API Platform

> **Document Status**: Draft v3.0 — Expanded (ARB-Ready)
> **Last Updated**: 2026-05-20
> **Classification**: CONFIDENTIAL — Internal Use Only
> **Audience**: Backend Engineers, Frontend Engineers, Mobile Integrators, QA
> **Input**: Phase 07 — Data Architecture (v5.0); Phase 05 — Security Architecture (v2.0)
> **Author Level**: Principal API Architect
> **Approval Gate**: 🏗️ Architecture Review Board (ARB) Final Sign-off

---

## Table of Contents

1. [Goal & Scope](#1-goal--scope)
2. [Key Decisions](#2-key-decisions)
3. [Documents Produced](#3-documents-produced)
4. [Architecture Artifacts](#4-architecture-artifacts)
   - [4.1 Service-to-Endpoint Matrix](#41-service-to-endpoint-matrix)
   - [4.2 Authentication & Authorization](#42-authentication--authorization)
   - [4.3 API Versioning Strategy](#43-api-versioning-strategy)
   - [4.4 Request / Response Envelopes](#44-request--response-envelopes)
   - [4.5 Idempotency Standard](#45-idempotency-standard)
   - [4.6 Pagination Standard](#46-pagination-standard)
   - [4.7 Rate Limiting & Throttling](#47-rate-limiting--throttling)
   - [4.8 Error Catalog](#48-error-catalog)
   - [4.9 Webhook / Async Notifications](#49-webhook--async-notifications)
   - [4.10 API Deprecation & Sunset Policy](#410-api-deprecation--sunset-policy)
   - [4.11 Content Negotiation & Headers](#411-content-negotiation--headers)
5. [Example Deliverables](#5-example-deliverables)
6. [Key Questions](#6-key-questions)
7. [Implementation Tasks](#7-implementation-tasks)
8. [Common Mistakes](#8-common-mistakes)
9. [KPIs & Exit Criteria](#9-kpis--exit-criteria)
10. [Connection to Next Phase](#10-connection-to-next-phase)

---

## 1. Goal & Scope

### 1.1 Goal

Define the **complete, API-First contract** for the Payment API Platform before a single line of service code is written. Every endpoint is specified via an OpenAPI 3.1 specification stored in `docs/cross-cutting/api/specs/`, validated by automated CI linting, and reviewed by ARB. The API contracts serve as the binding source of truth for backend implementation, frontend integration, mobile SDKs, QA test automation, and partner onboarding.

This document aligns three upstream phases:

| Upstream Phase | API Design Dependency |
|---------------|----------------------|
| **Phase 05 — Security Architecture** | Auth schemes (Bearer JWT, API keys, mTLS), RBAC scope model, rate limiting thresholds, CORS policy |
| **Phase 06 — High-Level Architecture** | Service boundaries (17 microservices, 4 bounded contexts), consistency models, idempotency mechanism |
| **Phase 07 — Data Architecture** | Account typology, journal entry structure, `create_journal_entry` stored procedure, `wallet_balances` projection, `idempotency_keys` table |

### 1.2 Scope

This document covers:

- **Synchronous REST API** — 28+ endpoints across 6 resource domains
- **Async Webhook API** — 12 event types, delivery protocol, retry semantics, signature verification
- **Cross-cutting concerns** — Auth, pagination, rate limiting, idempotency, versioning, deprecation, error handling
- **17 microservices** across 4 bounded contexts (Identity & Access, Financial Core, Commerce, Platform)
- **All environments**: Production, staging, sandbox, DR

---

## 2. Key Decisions

| # | Decision | Rationale | Trade-offs |
|---|----------|-----------|------------|
| D01 | **Strict REST Semantics** | APIs utilize standard HTTP verbs (`GET`, `POST`, `PATCH`, `DELETE`) mapping cleanly to resources. RPC-style paths (`/executePayment`) are restricted. | Standardizes tooling; limits flexibility for complex multi-resource operations. |
| D02 | **Mandatory Idempotency Headers** | Every state-mutating request (`POST`, `PATCH`, `DELETE`) MUST include a unique `Idempotency-Key` header. Missing keys yield immediate `400 Bad Request`. | Prevents double-charging; adds client implementation burden. |
| D03 | **Dual-Layer API Versioning** | URI route (`/v1/`) for structural breaking changes + `API-Version: 2026-05-20` header for additive schema evolution. Headers default to the latest version if omitted. | Backward-compatible within the same URI version; enables gradual client migration. |
| D04 | **Deterministic Error-to-DB Mapping** | DB-layer exceptions (`insufficient_funds`, `account_not_found`) map to structured API error responses at the service boundary. Error codes are documented, not leaky. | Debuggable without exposing internals. |
| D05 | **Cursor-Based Pagination** | All list endpoints use `cursor` + `limit` parameters. Offset-based pagination is explicitly disallowed. | Consistent reads under heavy insert load; cursor encodes position in the underlying sequence. |
| D06 | **Webhook-First Async Model** | State changes that external parties need to react to are delivered via signed webhook notifications with at-least-once delivery semantics. | Decouples polling; requires client webhook endpoint infrastructure. |
| D07 | **Unified Error Envelope** | All errors follow a single JSON structure: `{"error": {"type": "...", "code": "...", "message": "...", "param": "...", "doc_url": "..."}}` with a unique `request_id`. | Machine-parsable; matches Stripe error model. |
| D08 | **Rate Limiting via Token Bucket** | Enforced at API Gateway (Kong/Envoy). Public IP: 5 req/s; Authenticated: 20 req/s (configurable per endpoint). Headers `X-RateLimit-*` returned on every response. | Prevents abuse; adds per-endpoint fine-tuning complexity. |
| D09 | **12-Month Deprecation Window** | Breaking changes require a minimum 12-month notice with the `Sunset` header and migration guide. Additive changes are deployed within the same URI version. | Predictable client lifecycle; delays cleanup of old versions. |
| D10 | **OpenAPI 3.1 as Source of Truth** | All endpoint definitions, request/response schemas, and examples live in `docs/cross-cutting/api/specs/*.yaml`. Generated SDKs, Postman collections, and documentation are derived from these specs. | Single source of truth; requires disciplined spec maintenance. |

---

## 3. Documents Produced

This phase produces the following artifacts. **All must exist before ARB sign-off.**

| Document | Location | Status |
|----------|----------|--------|
| **API Design Reference** | `docs/stages/B-domain-architecture/08-api-design.md` (this document) | ✅ v3.0 |
| **Payments API OpenAPI Spec** | `docs/cross-cutting/api/specs/payments-api.yaml` | 🚧 Pending |
| **Wallets API OpenAPI Spec** | `docs/cross-cutting/api/specs/wallets-api.yaml` | 🚧 Pending |
| **Refunds & Payouts API OpenAPI Spec** | `docs/cross-cutting/api/specs/refunds-payouts-api.yaml` | 🚧 Pending |
| **Identity & Auth API OpenAPI Spec** | `docs/cross-cutting/api/specs/auth-api.yaml` | 🚧 Pending |
| **Webhooks API OpenAPI Spec** | `docs/cross-cutting/api/specs/webhooks-api.yaml` | 🚧 Pending |
| **API Style Guide** | `docs/cross-cutting/api/api-style-guide.md` | 🚧 Pending |
| **API Catalog (Index)** | `docs/cross-cutting/api/api-catalog.md` | 🚧 Pending |

---

## 4. Architecture Artifacts

### 4.1 Service-to-Endpoint Matrix

#### 4.1.1 Payments Resource

| Action | Method | Path | Auth Scope | Idempotency | DB Mapping |
|--------|--------|------|-----------|-------------|-----------|
| Create Payment | `POST` | `/v1/payments` | `write:payments` | **Required** | Calls `create_journal_entry(p_idempotency_key, ...)` |
| Retrieve Payment | `GET` | `/v1/payments/{payment_id}` | `read:payments` | N/A | `SELECT` from `journal_entries` + `journal_lines` |
| Update Payment Metadata | `PATCH` | `/v1/payments/{payment_id}` | `write:payments` | **Required** | `UPDATE journal_entries SET description`, metadata-only |
| Cancel Payment | `POST` | `/v1/payments/{payment_id}/cancel` | `write:payments` | **Required** | Reverse journal entry via `create_journal_entry` |
| List Payments | `GET` | `/v1/payments` | `read:payments` | N/A | Paginated `SELECT` on `journal_entries` (via OpenSearch or DB) |

#### 4.1.2 Refunds Resource

| Action | Method | Path | Auth Scope | Idempotency | Notes |
|--------|--------|------|-----------|-------------|-------|
| Create Refund | `POST` | `/v1/payments/{payment_id}/refunds` | `write:refunds` | **Required** | Validates original payment status. Creates reversal journal entry. |
| Retrieve Refund | `GET` | `/v1/refunds/{refund_id}` | `read:refunds` | N/A | Linked to original `payment_id` in response. |
| List Refunds | `GET` | `/v1/refunds` | `read:refunds` | N/A | Filterable by `payment_id`, `status`, `created` range. |

#### 4.1.3 Wallets Resource

| Action | Method | Path | Auth Scope | Idempotency | DB Mapping |
|--------|--------|------|-----------|-------------|-----------|
| Get Wallet Balances | `GET` | `/v1/wallets/balances` | `read:wallets` | N/A | Reads `wallet_balances` projection. Returns all currency balances for authenticated user. |
| Get Wallet Balance | `GET` | `/v1/wallets/balances/{currency}` | `read:wallets` | N/A | Single-currency balance from `wallet_balances`. |
| Top-Up Wallet | `POST` | `/v1/wallets/top-up` | `write:wallets` | **Required** | Funds-in journal entry. Source: external bank / payment gateway. |
| Withdraw from Wallet | `POST` | `/v1/wallets/withdraw` | `write:wallets` | **Required** | Funds-out journal entry. Destination: external bank account. |
| Get Wallet Details | `GET` | `/v1/wallets` | `read:wallets` | N/A | Account metadata: `account_type`, `currency`, `created_at`. |
| Freeze Wallet | `POST` | `/v1/wallets/{wallet_id}/freeze` | `admin:wallets` | **Required** | Sets account-level freeze flag. Admin-only (SUPPORT_LEAD+). |
| Unfreeze Wallet | `POST` | `/v1/wallets/{wallet_id}/unfreeze` | `admin:wallets` | **Required** | Clears account-level freeze flag. Admin-only. |

#### 4.1.4 Transactions Resource

| Action | Method | Path | Auth Scope | Notes |
|--------|--------|------|-----------|-------|
| List Transactions | `GET` | `/v1/transactions` | `read:transactions` | Cursor-paginated. Filterable by `account_id`, `type` (debit/credit), `currency`, `created` range. |
| Retrieve Transaction | `GET` | `/v1/transactions/{entry_id}` | `read:transactions` | Full journal entry with all lines. |

#### 4.1.5 Payouts Resource

| Action | Method | Path | Auth Scope | Idempotency | Notes |
|--------|--------|------|-----------|-------------|-------|
| Create Payout | `POST` | `/v1/payouts` | `write:payouts` | **Required** | Merchant-initiated. Moves funds from merchant wallet to external bank. |
| Retrieve Payout | `GET` | `/v1/payouts/{payout_id}` | `read:payouts` | N/A | Status: pending, in_transit, paid, failed. |
| List Payouts | `GET` | `/v1/payouts` | `read:payouts` | N/A | Filterable by status, created range. |
| Cancel Payout | `POST` | `/v1/payouts/{payout_id}/cancel` | `write:payouts` | **Required** | Only cancellable when status is `pending`. |

#### 4.1.6 Webhooks Management Resource

| Action | Method | Path | Auth Scope | Notes |
|--------|--------|------|-----------|-------|
| Register Webhook Endpoint | `POST` | `/v1/webhooks/endpoints` | `admin:webhooks` | Registers a URL + event subscriptions. Returns `webhook_secret`. |
| List Endpoints | `GET` | `/v1/webhooks/endpoints` | `admin:webhooks` | All registered webhook endpoints. |
| Retrieve Endpoint | `GET` | `/v1/webhooks/endpoints/{endpoint_id}` | `admin:webhooks` | Single endpoint details + event subscriptions. |
| Update Endpoint | `PATCH` | `/v1/webhooks/endpoints/{endpoint_id}` | `admin:webhooks` | Update URL, enabled/disabled, event subscriptions. |
| Delete Endpoint | `DELETE` | `/v1/webhooks/endpoints/{endpoint_id}` | `admin:webhooks` | Permanently removes webhook registration. |
| Rotate Secret | `POST` | `/v1/webhooks/endpoints/{endpoint_id}/rotate-secret` | `admin:webhooks` | Returns new `webhook_secret`. Old secret invalidated after 24h. |
| List Delivery Attempts | `GET` | `/v1/webhooks/endpoints/{endpoint_id}/deliveries` | `admin:webhooks` | Paginated delivery log with status codes and timestamps. |
| Retry Delivery | `POST` | `/v1/webhooks/endpoints/{endpoint_id}/deliveries/{delivery_id}/retry` | `admin:webhooks` | Manually retry a failed webhook delivery. |

#### 4.1.7 Admin / Platform Resource

| Action | Method | Path | Auth Scope | Notes |
|--------|--------|------|-----------|-------|
| Health Check | `GET` | `/v1/health` | N/A | Public. Returns `{"status": "ok"}` + dependency statuses. |
| API Status | `GET` | `/v1/status` | N/A | Public. Returns API version, region, uptime. |
| List API Keys | `GET` | `/v1/api-keys` | `admin:platform` | All issued API keys (masked). |
| Create API Key | `POST` | `/v1/api-keys` | `admin:platform` | Generates new API key for developer / partner. |
| Revoke API Key | `DELETE` | `/v1/api-keys/{key_id}` | `admin:platform` | Immediate revocation. |
| Get Rate Limit Config | `GET` | `/v1/admin/rate-limits` | `admin:platform` | Current rate limit configuration per endpoint. |

---

### 4.2 Authentication & Authorization

All API endpoints require authentication unless explicitly marked as public. The platform supports three authentication mechanisms:

| Mechanism | Use Case | Header Format | Defined In |
|-----------|----------|---------------|------------|
| **Bearer JWT (OAuth 2.0)** | End-user / merchant sessions | `Authorization: Bearer eyJhbGciOiJSUzI1NiIs...` | Phase 05 §4 |
| **API Key (Secret)** | Server-to-server integrations, partners | `Authorization: Bearer sk_live_aBc123...` | Phase 05 §8 |
| **mTLS** | Internal service-to-service | TLS client certificate (SPIFFE) | Phase 05 §6 |

**Authorization Scopes** are granular and resource-oriented, following the RBAC model defined in Phase 05 §5:

| Scope Pattern | Example Scopes | Roles |
|---------------|---------------|-------|
| `read:{resource}` | `read:payments`, `read:wallets`, `read:transactions` | USER, MERCHANT, SUPPORT_AGENT |
| `write:{resource}` | `write:payments`, `write:refunds`, `write:payouts` | USER, MERCHANT |
| `admin:{resource}` | `admin:wallets`, `admin:webhooks`, `admin:platform` | SUPPORT_LEAD, FINANCE_ADMIN, PLATFORM_ADMIN |

**Scope Enforcement Flow**:
1. API Gateway (Kong/Envoy) validates the JWT or API key at the edge.
2. Gateway enriches the request with a signed `X-Auth-User` and `X-Auth-Scopes` header.
3. Each microservice validates scope claims against the endpoint's required scope.
4. 403 returns if scope is insufficient; 401 returns if no valid credential presented.

---

### 4.3 API Versioning Strategy

The platform uses a **dual-layer versioning model**:

| Layer | Mechanism | Use Case | Example |
|-------|-----------|----------|---------|
| **URI Version** (Major/Structural) | `/v1/`, `/v2/` | Breaking structural changes: renamed resources, removed endpoints, changed auth model | `/v1/payments` → `/v2/payments` |
| **Header Version** (Semantic/Additive) | `API-Version: 2026-05-20` | Additive schema changes: new optional fields, new endpoints within same URI version, behavioral refinements | Same URI, different response shape |

**Version Resolution Rules**:

1. If `API-Version` header is present, use it directly.
2. If absent, default to the **latest stable version** for the given URI version.
3. Response always includes `API-Version: YYYY-MM-DD` reflecting the version that handled the request.
4. URI version and header version must be compatible — a `v1` URI cannot serve a `v2`-era schema.

**Changelog & Migration**:

- Every API version change is documented in `docs/cross-cutting/api/CHANGELOG.md`.
- Breaking changes: 12 months' notice. `Sunset: Sat, DD Mon YYYY 00:00:00 GMT` header appears on deprecated endpoints.
- Additive changes: deployed immediately, backward-compatible within the same URI version.
- Client SDKs are regenerated on every version release from OpenAPI specs.

---

### 4.4 Request / Response Envelopes

#### 4.4.1 Required Request Headers

| Header | Required For | Value |
|--------|-------------|-------|
| `Authorization` | All authenticated endpoints | `Bearer {token}` or `Bearer {api_key}` |
| `Content-Type` | `POST`, `PATCH` | `application/json` |
| `Idempotency-Key` | `POST`, `PATCH`, `DELETE` | UUID v4 (36-char). Max 255 chars. |
| `Accept` | All | `application/json` (default) |
| `API-Version` | Optional | `YYYY-MM-DD` date string |

#### 4.4.2 Success Response Envelope

```json
{
  "id": "pay_xyz987654",
  "object": "payment",
  "amount": 15000,
  "currency": "VND",
  "status": "succeeded",
  "created": 1713024000,
  "description": "Coffee Purchase",
  "source_account_id": "acc_001_wallet",
  "destination_account_id": "acc_002_merchant",
  "metadata": {},
  "livemode": true
}
```

- Top-level resource wrapper: the `object` field indicates the resource type.
- Timestamps: Unix epoch seconds (UTC).
- `livemode`: boolean indicating live (`true`) vs test/sandbox (`false`).

#### 4.4.3 List Response Envelope (Paginated)

```json
{
  "object": "list",
  "data": [
    { "id": "pay_001", "object": "payment", ... },
    { "id": "pay_002", "object": "payment", ... }
  ],
  "has_more": true,
  "next_cursor": "cUR_4d2F8kQzLp",
  "url": "/v1/payments?limit=10&cursor=cUR_4d2F8kQzLp"
}
```

- `data`: Array of resource objects. Always present, may be empty.
- `has_more`: `true` if more results exist beyond `next_cursor`.
- `next_cursor`: Opaque string. `null` if `has_more` is `false`.
- `url`: Canonical URL for the next page (convenience).

#### 4.4.4 Error Response Envelope

```json
{
  "error": {
    "type": "invalid_request_error",
    "code": "insufficient_funds",
    "message": "The source account lacks sufficient funds for this transaction.",
    "param": "amount",
    "doc_url": "https://docs.payments.platform.com/errors#insufficient_funds"
  },
  "request_id": "req_aBcDeFgHiJkL"
}
```

- `type`: High-level error category (see §4.8).
- `code`: Machine-readable error code. Stable across versions.
- `message`: Human-readable description. May change for clarity.
- `param`: Optional. The request parameter that triggered the error.
- `doc_url`: Optional. Link to documentation for this error code.
- `request_id`: UUID v4. Unique per request for support tracing.

---

### 4.5 Idempotency Standard

Idempotency is enforced at the **API Gateway + DB** boundary, as defined in Phase 06 §5:

| Aspect | Specification |
|--------|--------------|
| **Header Name** | `Idempotency-Key` |
| **Value Format** | UUID v4 (recommended). Any string ≤ 255 chars accepted. |
| **Scope** | Per API key. Keys are unique within a single account, not globally. |
| **TTL** | 24 hours from last use. Keys expire automatically in `idempotency_keys` table. |
| **Required On** | `POST`, `PATCH`, `DELETE`. Optional (ignored) on `GET`. |
| **Missing Key** | `400 Bad Request` — `code: idempotency_key_missing` |
| **Replay (Same Key, Same Body)** | Returns the original response body + `Idempotent-Replayed: true` header. |
| **Replay (Same Key, Different Body)** | `409 Conflict` — `code: idempotency_key_mismatch` |
| **Concurrent (Key Still Processing)** | `409 Conflict` — `code: idempotency_key_in_progress` |

**Implementation**: Idempotency is enforced at two layers:
1. **API Gateway**: Fast-path cache (Redis, 1-hour TTL) for near-instant replay detection.
2. **Database**: `idempotency_keys` table in PostgreSQL with `UNIQUE(idempotency_key, user_id)` — the ultimate arbiter (Phase 07 §4.2).

**Client Guidance**:
- Generate a new UUID v4 for every distinct operation.
- Retry the **exact same request** (headers + body) on network failure.
- Do NOT reuse idempotency keys for different operations.

---

### 4.6 Pagination Standard

All list endpoints use **cursor-based pagination** to guarantee consistent reads under high transaction insert rates (Phase 06 §2).

| Parameter | Type | Default | Max | Description |
|-----------|------|---------|-----|-------------|
| `cursor` | String | `null` (first page) | — | Opaque cursor from `next_cursor` of previous response |
| `limit` | Integer | 20 | 100 | Maximum number of objects to return |

**Cursor Encoding**: Cursors are base64-encoded and encode the `account_sequence` (for account-scoped lists) or `created_at` timestamp + `entry_id` (for time-scoped lists). Clients MUST treat cursors as opaque strings — parsing or constructing cursors will break on schema changes.

**Filtering Parameters** (where applicable):

| Parameter | Type | Example | Description |
|-----------|------|---------|-------------|
| `created[gte]` | Integer (Unix) | `1713024000` | Created ≥ timestamp |
| `created[lte]` | Integer (Unix) | `1713110400` | Created ≤ timestamp |
| `status` | String | `succeeded` | Filter by resource status |
| `currency` | String (3-char) | `VND` | Filter by currency |
| `type` | String | `DEBIT` | Filter by entry type (transactions only) |

**Response Headers**:
- `Link`: RFC 5988-compliant pagination links (`rel="next"`, `rel="prev"`).

---

### 4.7 Rate Limiting & Throttling

Rate limiting is enforced at the API Gateway (Kong/Envoy) using a **token bucket algorithm**, as specified in Phase 05 §8.

| Tier | Rate Limit | Burst Allowance | Scope |
|------|-----------|----------------|-------|
| **Public (Unauthenticated)** | 5 req/s | +3 burst | Per source IP |
| **Authenticated (User/Merchant)** | 20 req/s | +10 burst | Per API key / user ID |
| **Admin (Internal)** | 50 req/s | +25 burst | Per admin principal |
| **Webhook Delivery** | N/A (outbound) | — | Not rate-limited on send |

**Response Headers** (returned on every response):

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Maximum requests allowed in the window (e.g., `20`) |
| `X-RateLimit-Remaining` | Requests remaining in the current window |
| `X-RateLimit-Reset` | Unix timestamp (seconds) when the window resets |
| `Retry-After` | Seconds until next allowed request (only on 429) |

**Rate Limit Exceeded Response**:

```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 20
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1713024060
Retry-After: 12

{
  "error": {
    "type": "rate_limit_error",
    "code": "rate_limit_exceeded",
    "message": "Too many requests. Rate limit of 20 req/s exceeded. Retry after 12 seconds.",
    "doc_url": "https://docs.payments.platform.com/errors#rate_limit_exceeded"
  },
  "request_id": "req_7f3aB9cD1eF2"
}
```

**Per-Endpoint Tuning**: Individual endpoints may have stricter limits. The admin API (`/v1/admin/rate-limits`) exposes current configuration.

---

### 4.8 Error Catalog

All error responses use the unified envelope defined in §4.4.4. Error codes are stable and documented — clients can safely build logic around them.

#### 4.8.1 Error Types

| Type | HTTP Range | Meaning |
|------|-----------|---------|
| `api_error` | 500 | Unexpected server error. Retry with exponential backoff. |
| `authentication_error` | 401 | Invalid or expired credentials. |
| `authorization_error` | 403 | Valid credentials but insufficient permissions. |
| `invalid_request_error` | 400, 404, 409, 422 | Malformed request or invalid parameter. Fix the request. |
| `rate_limit_error` | 429 | Rate limit exceeded. Retry after `Retry-After` seconds. |
| `idempotency_error` | 400, 409 | Idempotency key missing, mismatched, or in progress. |

#### 4.8.2 Error Code Registry

| HTTP | Code | Type | Message Template | DB Mapping |
|------|------|------|-----------------|------------|
| 400 | `idempotency_key_missing` | `invalid_request_error` | "The request requires an Idempotency-Key header." | N/A (Gateway) |
| 400 | `idempotency_key_invalid` | `invalid_request_error` | "The Idempotency-Key value is invalid." | N/A (Gateway) |
| 400 | `invalid_parameter` | `invalid_request_error` | "Invalid parameter: {param}" | N/A |
| 400 | `missing_required_parameter` | `invalid_request_error` | "Missing required parameter: {param}" | N/A |
| 400 | `invalid_currency` | `invalid_request_error` | "Currency '{currency}' is not supported." | `accounts.currency CHECK` |
| 400 | `invalid_amount` | `invalid_request_error` | "Amount must be a positive integer representing the minor currency unit." | `journal_lines.amount > 0` |
| 400 | `expired_idempotency_key` | `idempotency_error` | "The Idempotency-Key has expired (24h TTL)." | `idempotency_keys.locked_until` |
| 401 | `invalid_api_key` | `authentication_error` | "Invalid API key provided." | N/A (Gateway) |
| 401 | `token_expired` | `authentication_error` | "Access token has expired." | N/A (Gateway) |
| 401 | `invalid_token` | `authentication_error` | "Token signature verification failed." | N/A (Gateway) |
| 402 | `payment_method_declined` | `invalid_request_error` | "Payment method was declined by the issuer." | External gateway |
| 402 | `insufficient_funds` | `invalid_request_error` | "The source account lacks sufficient funds for this transaction." | DB trigger: `new_bal < 0 AND allow_negative = FALSE` |
| 403 | `account_frozen` | `authorization_error` | "The account is frozen and cannot process transactions." | Account freeze flag |
| 403 | `scope_insufficient` | `authorization_error` | "The provided credentials do not have the required scope: {scope}" | N/A (Gateway) |
| 404 | `account_not_found` | `invalid_request_error` | "Account '{account_id}' not found." | `accounts` lookup failure |
| 404 | `payment_not_found` | `invalid_request_error` | "Payment '{payment_id}' not found." | `journal_entries` lookup failure |
| 404 | `refund_not_found` | `invalid_request_error` | "Refund '{refund_id}' not found." | Refund record lookup failure |
| 409 | `idempotency_replayed` | `idempotency_error` | "Request already processed. Response matches original." | `idempotency_keys.status = 'COMPLETED'` + body hash match |
| 409 | `idempotency_key_mismatch` | `idempotency_error` | "Idempotency key reused with a different request body." | `idempotency_keys` hash mismatch |
| 409 | `idempotency_key_in_progress` | `idempotency_error` | "A request with this Idempotency-Key is currently processing." | `idempotency_keys.status = 'STARTED'` |
| 409 | `duplicate_transaction` | `invalid_request_error` | "A transaction with these parameters already exists." | DB UNIQUE constraint |
| 409 | `payment_already_refunded` | `invalid_request_error` | "This payment has already been fully refunded." | Business rule |
| 422 | `double_entry_imbalance` | `api_error` | "Internal accounting error: journal does not balance." | `trg_verify_double_entry_statement` |
| 429 | `rate_limit_exceeded` | `rate_limit_error` | "Too many requests. Retry after {n} seconds." | N/A (Gateway token bucket) |
| 500 | `internal_error` | `api_error` | "An unexpected error occurred. The team has been notified." | Unhandled exception |
| 503 | `service_unavailable` | `api_error` | "Service temporarily unavailable. Retry with backoff." | Circuit breaker open |

---

### 4.9 Webhook / Async Notifications

The platform pushes state-change notifications to registered webhook endpoints using a signed, at-least-once delivery model.

#### 4.9.1 Webhook Event Types

| Event | Category | Trigger | Payload Summary |
|-------|----------|---------|----------------|
| `payment.created` | Payments | Payment record written to journal | `payment_id`, `amount`, `currency`, `status` |
| `payment.succeeded` | Payments | Double-entry validated, balance updated | `payment_id`, `amount`, `currency`, `ledger_entry_id` |
| `payment.failed` | Payments | Journal entry rejected (insufficient funds, account frozen, etc.) | `payment_id`, `error_code`, `error_message` |
| `payment.canceled` | Payments | Payment successfully reversed | `payment_id`, `canceled_at` |
| `refund.created` | Refunds | Refund journal entry created | `refund_id`, `payment_id`, `amount`, `currency` |
| `refund.succeeded` | Refunds | Refund double-entry validated | `refund_id`, `payment_id`, `amount` |
| `refund.failed` | Refunds | Refund journal rejected | `refund_id`, `error_code` |
| `payout.created` | Payouts | Payout initiated | `payout_id`, `amount`, `currency`, `destination` |
| `payout.succeeded` | Payouts | Funds transferred to external bank | `payout_id`, `settlement_ref` |
| `payout.failed` | Payouts | External bank transfer failed | `payout_id`, `error_code` |
| `wallet.frozen` | Wallets | Account frozen by admin | `wallet_id`, `frozen_at`, `reason` |
| `wallet.unfrozen` | Wallets | Account unfrozen by admin | `wallet_id`, `unfrozen_at` |

#### 4.9.2 Delivery Protocol

| Aspect | Specification |
|--------|--------------|
| **Method** | `POST` (JSON payload) |
| **Content-Type** | `application/json` |
| **Signature Header** | `Webhook-Signature: t=1713024000,v1=2d4b6...` |
| **Signature Algorithm** | HMAC-SHA256. Signature = `HMAC(webhook_secret, "{timestamp}.{payload}")` |
| **Signature Scheme** | `v1` (prefixed). Multiple signatures supported for secret rotation. |
| **Timeout** | 10 seconds per delivery attempt |
| **Expected Response** | `200 OK` (any body). Non-2xx = delivery failure. |
| **Retry Policy** | Exponential backoff: 0s, 30s, 2m, 5m, 15m, 1h, 4h, 8h → DLQ |
| **Max Retries** | 8 attempts over 24 hours, then dead letter |
| **Ordering** | Best-effort ordering per event type. No cross-type ordering guarantee. |
| **Idempotency** | Each delivery includes `Webhook-Id: evt_xxx` header. Clients deduplicate on this ID. |

#### 4.9.3 Webhook Payload Example

```http
POST /webhook-receiver HTTP/1.1
Host: partner.example.com
Content-Type: application/json
Webhook-Id: evt_aBc123DeF456
Webhook-Signature: t=1713024000,v1=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
Webhook-Event: payment.succeeded

{
  "id": "evt_aBc123DeF456",
  "object": "event",
  "type": "payment.succeeded",
  "created": 1713024000,
  "livemode": true,
  "data": {
    "object": {
      "id": "pay_xyz987654",
      "object": "payment",
      "amount": 15000,
      "currency": "VND",
      "status": "succeeded",
      "source_account_id": "acc_001_wallet",
      "destination_account_id": "acc_002_merchant",
      "created": 1713024000
    }
  },
  "request_id": "req_7f3aB9cD1eF2"
}
```

#### 4.9.4 Client Verification

Clients MUST verify webhook signatures before processing the payload. Pseudo-code:

```python
import hmac, hashlib

def verify_signature(payload, signature_header, secret):
    # Parse: t=1713024000,v1=9f86d081...
    timestamp, signature = parse_header(signature_header)
    signed_payload = f"{timestamp}.{payload}".encode()
    expected = hmac.new(secret.encode(), signed_payload, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)
```

---

### 4.10 API Deprecation & Sunset Policy

| Policy | Value |
|--------|-------|
| **Deprecation Notice Period** | Minimum 12 months before removal for breaking changes |
| **Deprecation Header** | `Sunset: Sat, 15 May 2027 00:00:00 GMT` (RFC 8594) |
| **Deprecation Link** | `Deprecation: true` + `Link: <https://docs...>; rel="deprecation"` |
| **Migration Guide** | Required per deprecated endpoint. Published in `docs/cross-cutting/api/CHANGELOG.md` |
| **Additive Changes** | New optional fields, new endpoints — deployed without deprecation notice |
| **Version Lifecycle** | `Active` → `Deprecated` (12 months) → `Sunset` (returns 410 Gone) |
| **Communication** | Email to registered API consumers 12, 6, 3, and 1 month before sunset |

**Deprecated Endpoint Response Headers**:

```http
HTTP/1.1 200 OK
Deprecation: true
Sunset: Sat, 15 May 2027 00:00:00 GMT
Link: <https://docs.payments.platform.com/migrations/v1-to-v2-payments>; rel="deprecation"
```

**Post-Sunset Response**:

```http
HTTP/1.1 410 Gone
{
  "error": {
    "type": "invalid_request_error",
    "code": "endpoint_deprecated",
    "message": "This API version has been sunset. Please migrate to /v2/payments.",
    "doc_url": "https://docs.payments.platform.com/migrations/v1-to-v2-payments"
  },
  "request_id": "req_..."
}
```

---

### 4.11 Content Negotiation & Headers

| Aspect | Specification |
|--------|--------------|
| **Supported Content Types** | `application/json` only. Any other `Content-Type` or `Accept` returns `415 Unsupported Media Type`. |
| **Character Encoding** | UTF-8 exclusively. |
| **Response Compression** | `gzip` via `Accept-Encoding: gzip`. Gateway handles transparently. |
| **CORS** | Per Phase 05 §8. `Access-Control-Allow-Origin` restricted to registered origins. |
| **Request ID** | Every response includes `X-Request-Id: req_...`. Clients should log this for support escalations. |
| **Strict Transport Security** | `Strict-Transport-Security: max-age=31536000; includeSubDomains` enforced at edge. |
| **Cache Control** | `Cache-Control: no-store` on all transactional endpoints. Read endpoints may permit short-lived caching (30s max). |

---

## 5. Example Deliverables

### 5.1 POST /v1/payments — Success

```http
POST /v1/payments HTTP/1.1
Host: api.payments.platform.com
Authorization: Bearer sk_live_aBc123XyZ789...
Idempotency-Key: cb174dc0-2ed2-4b2a-bf35-a131015fc65e
Content-Type: application/json
API-Version: 2026-05-20

{
  "source_account_id": "acc_001_wallet",
  "destination_account_id": "acc_002_merchant",
  "amount": 15000,
  "currency": "VND",
  "description": "Coffee Purchase"
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json
API-Version: 2026-05-20
X-Request-Id: req_aBcDeFgHiJkL

{
  "id": "pay_xyz987654",
  "object": "payment",
  "amount": 15000,
  "currency": "VND",
  "status": "succeeded",
  "source_account_id": "acc_001_wallet",
  "destination_account_id": "acc_002_merchant",
  "description": "Coffee Purchase",
  "created": 1713024000,
  "livemode": true,
  "metadata": {}
}
```

### 5.2 POST /v1/payments — Insufficient Funds (422)

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/json
X-Request-Id: req_7f3aB9cD1eF2

{
  "error": {
    "type": "invalid_request_error",
    "code": "insufficient_funds",
    "message": "The source account lacks sufficient funds for this transaction.",
    "param": "amount",
    "doc_url": "https://docs.payments.platform.com/errors#insufficient_funds"
  },
  "request_id": "req_7f3aB9cD1eF2"
}
```

### 5.3 POST /v1/payments — Idempotency Replay (409)

```http
POST /v1/payments HTTP/1.1
Idempotency-Key: cb174dc0-2ed2-4b2a-bf35-a131015fc65e
Authorization: Bearer sk_live_aBc123XyZ789...
Content-Type: application/json

{
  "source_account_id": "acc_001_wallet",
  "destination_account_id": "acc_002_merchant",
  "amount": 15000,
  "currency": "VND",
  "description": "Coffee Purchase"
}
```

```http
HTTP/1.1 200 OK
Idempotent-Replayed: true
Content-Type: application/json
X-Request-Id: req_RePlAyEd001

{
  "id": "pay_xyz987654",
  "object": "payment",
  "amount": 15000,
  "currency": "VND",
  "status": "succeeded",
  "created": 1713024000,
  ...
}
```

### 5.4 POST /v1/payments — Idempotency Key Missing (400)

```http
POST /v1/payments HTTP/1.1
Authorization: Bearer sk_live_aBc123XyZ789...
Content-Type: application/json

{
  "source_account_id": "acc_001_wallet",
  "destination_account_id": "acc_002_merchant",
  "amount": 15000,
  "currency": "VND"
}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json
X-Request-Id: req_NoKeyError01

{
  "error": {
    "type": "invalid_request_error",
    "code": "idempotency_key_missing",
    "message": "The request requires an Idempotency-Key header.",
    "doc_url": "https://docs.payments.platform.com/errors#idempotency_key_missing"
  },
  "request_id": "req_NoKeyError01"
}
```

### 5.5 GET /v1/transactions — Paginated List

```http
GET /v1/transactions?limit=2&account_id=acc_001_wallet&type=DEBIT HTTP/1.1
Host: api.payments.platform.com
Authorization: Bearer sk_live_aBc123XyZ789...
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Link: </v1/transactions?limit=2&cursor=cUR_Xp9k2MzQa>; rel="next"
X-Request-Id: req_ListTxns001

{
  "object": "list",
  "data": [
    {
      "id": "entry_4fA1bC2dE3",
      "object": "transaction",
      "account_id": "acc_001_wallet",
      "entry_type": "DEBIT",
      "amount": 15000,
      "currency": "VND",
      "running_balance": 485000,
      "description": "Coffee Purchase",
      "account_sequence": 42,
      "created": 1713024000
    },
    {
      "id": "entry_3eF4bG5hI6",
      "object": "transaction",
      "account_id": "acc_001_wallet",
      "entry_type": "DEBIT",
      "amount": 50000,
      "currency": "VND",
      "running_balance": 435000,
      "description": "Grab Ride Payment",
      "account_sequence": 43,
      "created": 1713025000
    }
  ],
  "has_more": true,
  "next_cursor": "cUR_Xp9k2MzQa",
  "url": "/v1/transactions?limit=2&account_id=acc_001_wallet&type=DEBIT&cursor=cUR_Xp9k2MzQa"
}
```

### 5.6 Rate Limit Exceeded (429)

```http
POST /v1/payments HTTP/1.1
Authorization: Bearer sk_live_aBc123XyZ789...
Idempotency-Key: fe817dc0-3ae3-5c3b-cg46-b242126gd76f
Content-Type: application/json

{ "source_account_id": "acc_001_wallet", "destination_account_id": "acc_002_merchant", "amount": 15000, "currency": "VND" }
```

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
X-RateLimit-Limit: 20
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1713024060
Retry-After: 12

{
  "error": {
    "type": "rate_limit_error",
    "code": "rate_limit_exceeded",
    "message": "Too many requests. Rate limit of 20 req/s exceeded. Retry after 12 seconds.",
    "doc_url": "https://docs.payments.platform.com/errors#rate_limit_exceeded"
  },
  "request_id": "req_RateLmtExcd01"
}
```

---

## 6. Key Questions

| # | Question | Answer |
|---|----------|--------|
| Q1 | Do we expose internal `hash_chain` or `account_sequence` values to API consumers? | No. Cryptographic ledger internals are abstracted. The API exports only standard timestamp (`created`) and business identifiers (`payment_id`, `entry_id`). |
| Q2 | How do we handle partial refunds? | `POST /v1/payments/{payment_id}/refunds` accepts an `amount` field ≤ remaining refundable amount. Multiple partial refunds are allowed until the full amount is exhausted. |
| Q3 | What happens when `API-Version` header conflicts with URI version? | URI version takes precedence. A `v2`-era `API-Version` header on a `/v1/` URI returns `400 Bad Request — code: version_mismatch`. |
| Q4 | How are webhook secrets rotated without downtime? | During rotation, both old and new secrets are valid for 24 hours. Webhook signatures include both `v1` (old) and `v2` (new) in the `Webhook-Signature` header. Clients should attempt `v2` first, fall back to `v1`. |
| Q5 | Can a client request a custom `limit` beyond 100 for paginated lists? | No. The server clamps `limit` to 100. Exceeding 100 introduces unpredictable latency. For bulk data access, use the batch export API (future). |
| Q6 | Are sandbox/test API keys distinguishable from live keys? | Yes. Test keys are prefixed `sk_test_`, live keys are prefixed `sk_live_`. The `livemode` flag in all responses reflects the key mode. Phase 05 §8 defines key prefixes. |
| Q7 | How do we handle double-entry imbalance at the API layer? | The DB `trg_verify_double_entry_statement` trigger (Phase 07 §4.3) raises a `CRITICAL` exception. At the API layer, this maps to `500 Internal Server Error — code: double_entry_imbalance`. This should never reach production under normal operation; it indicates a code bug. |
| Q8 | What is the SLA for API response times? | Per SLOs in Phase 02: P50 < 100ms, P95 < 250ms, P99 < 500ms for `POST /v1/payments`. Read endpoints: P50 < 50ms, P99 < 150ms. |

---

## 7. Implementation Tasks

### P0 — Critical Path (Must complete before ARB sign-off)

- [ ] **T01**: Generate OpenAPI 3.1 specification for Payments API (`payments-api.yaml`) with all request/response schemas, examples, and error codes.
- [ ] **T02**: Generate OpenAPI 3.1 specification for Wallets API (`wallets-api.yaml`).
- [ ] **T03**: Generate OpenAPI 3.1 specification for Refunds & Payouts API (`refunds-payouts-api.yaml`).
- [ ] **T04**: Generate OpenAPI 3.1 specification for Auth & Webhooks API (`auth-api.yaml`, `webhooks-api.yaml`).
- [ ] **T05**: Write the API Style Guide (`api-style-guide.md`) — naming conventions, header standards, error patterns, pagination template.
- [ ] **T06**: Write the API Catalog (`api-catalog.md`) — index of all endpoints by service with auth requirements.
- [ ] **T07**: Configure OpenAPI linting in CI pipeline (Spectral ruleset, Redocly). All specs must pass before merge.

### P1 — Required Before Phase 17 (Vertical Slice)

- [ ] **T08**: Map each endpoint to its corresponding DB stored procedure or query from Phase 07. Verify parameter alignment.
- [ ] **T09**: Define Postman / Insomnia collections for all endpoints (derived from OpenAPI specs).
- [ ] **T10**: Write API key generation and management service spec (aligned with Phase 05 auth model).
- [ ] **T11**: Define webhook signature verification test vectors for partner SDKs.
- [ ] **T12**: Generate API documentation site (from OpenAPI specs using Redoc/Scalar).

### P2 — Required Before Phase 25 (Production Readiness)

- [ ] **T13**: Generate server stubs and client SDKs from OpenAPI specs (Go server, TypeScript SDK, Java SDK, Python SDK).
- [ ] **T14**: Configure API diff tooling to detect breaking changes between spec versions.
- [ ] **T15**: Implement API Gateway route configuration from OpenAPI specs (declarative, not manual).

---

## 8. Common Mistakes

### 8.1 Design Mistakes

| Mistake | Consequence | Prevention |
|---------|-------------|-----------|
| **Leaking DB internals** | Returning literal PostgreSQL tracebacks or `account_sequence` values to API consumers. | Map all DB exceptions to standardized error codes at the service boundary (§4.8). |
| **Implicit currency assumption** | Assuming the API doesn't need `currency` and relying on account defaults. Currency is always explicit in requests. | `currency` field is required on all monetary requests. |
| **Offset-based pagination** | Missing or duplicating rows under concurrent inserts. | Cursor-based pagination only. `OFFSET` pagination is explicitly forbidden. |
| **Mixing resource and RPC paths** | Using paths like `/executePayment` or `/doRefund`. | Always use resource-oriented paths: `/v1/payments`, `/v1/payments/{id}/refunds`. |
| **Inconsistent timestamp formats** | Mixing ISO 8601 strings and Unix epoch integers. | All timestamps in API responses are Unix epoch seconds (integer). Request timestamps accept Unix epoch only. |
| **Missing idempotency on mutating operations** | Forgetting to require `Idempotency-Key` on `DELETE`. | Every `POST`, `PATCH`, `DELETE` endpoint requires idempotency (§4.5). |
| **Overloaded error messages** | Including stack traces, SQL, or internal identifiers in error messages. | Error messages are user-facing and stable. Internal details go to server logs, not API responses. |

### 8.2 Implementation Mistakes

| Mistake | Consequence | Prevention |
|---------|-------------|-----------|
| **Not deduplicating webhook events** | Processing the same payment.succeeded event twice. | Clients MUST use `Webhook-Id` header for deduplication (§4.9.3). |
| **Hardcoding API version in client** | Clients break on version upgrades because they expected a specific schema. | Clients should send `API-Version` header and handle `Deprecation`/`Sunset` headers gracefully. |
| **Ignoring `Retry-After` header** | Clients retry immediately on 429, worsening the rate limit situation. | Always respect `Retry-After` header with exponential backoff jitter. |

---

## 9. KPIs & Exit Criteria

| # | Criterion | Target | Measurement |
|---|-----------|--------|-------------|
| K01 | OpenAPI spec coverage | 100% of endpoints defined in §4.1 have a corresponding OpenAPI spec | CI lint check |
| K02 | Idempotency coverage | 100% of mutating endpoints (`POST`, `PATCH`, `DELETE`) require `Idempotency-Key` | OpenAPI spec validation |
| K03 | Error code documentation | All 24 error codes in §4.8 have a `doc_url` and are present in OpenAPI specs | CI lint check |
| K04 | Schema-to-DB alignment | Every API field maps to a Phase 07 DB column or transformation | Manual review checklist |
| K05 | OpenAPI linting pass | Zero Spectral/Redocly errors or warnings on all spec files | CI pipeline |
| K06 | Breaking change detection | `openapi-diff` on every spec change reports no breaking changes (or breaking changes are documented with a migration guide) | CI pipeline |
| K07 | Pagination consistency | 100% of list endpoints use cursor-based pagination with the standard envelope | Code review |
| K08 | Auth scope coverage | Every endpoint in §4.1 declares a required auth scope | OpenAPI spec `security` blocks |

**Exit Gate**: All K01–K08 must be ✅ before ARB sign-off.

---

## 10. Connection to Next Phase

This phase produces the **binding API contracts** that drive several downstream phases:

| Downstream Phase | How API Design Connects |
|-----------------|------------------------|
| **Phase 09 — Event Schema & Governance** | API state changes (`payment.succeeded`, `refund.created`) define the event catalog. The event envelope (§4.9.3) is derived from the API response model. |
| **Phase 10 — System Flows** | Every E2E flow diagram references the exact API endpoints, auth tokens, and idempotency keys defined here. |
| **Phase 12 — Infrastructure Design** | API Gateway (Kong/Envoy) route configuration is generated from OpenAPI specs. Rate limiting, CORS, and auth filter configurations are derived from §4.2, §4.7, and §4.11. |
| **Phase 13 — Platform Core** | The shared `@app/core` library implements request validation middleware, error envelope formatting, cursor parsing, and idempotency key extraction based on the standards defined here. |
| **Phase 14 — Testing Strategy** | Contract tests (Pact) are derived from OpenAPI specs. API fuzz tests target the error codes in §4.8. |
| **Phase 15 — Developer Platform** | Postman/Insomnia collections and mock servers are generated from OpenAPI specs for local development. |
| **Phase 16 — CI/CD** | OpenAPI linting, breaking change detection, and SDK generation are automated CI pipeline steps. |
| **Phase 17 — Vertical Slice** | The first end-to-end working flow implements `POST /v1/payments` → DB journal → webhook delivery, validating the full API contract. |

---

### 🛑 APPROVAL GATE → 🏗️ Architecture Review Board

**Checklist**:

- [ ] All OpenAPI spec files exist in `docs/cross-cutting/api/specs/` and pass CI linting
- [ ] All 28+ endpoints are documented with request/response schemas
- [ ] Error catalog (§4.8) is complete and consistent with Phase 07 DB constraints
- [ ] Idempotency standard (§4.5) aligns with Phase 06 concurrency model
- [ ] Auth scopes (§4.2) align with Phase 05 RBAC model
- [ ] Webhook event types (§4.9) cover all state transitions from Phase 07 journal entries
- [ ] API versioning strategy (§4.3) has a documented deprecation policy
- [ ] Pagination standard (§4.6) is consistently applied across all list endpoints
- [ ] Rate limiting (§4.7) thresholds match Phase 05 security requirements
- [ ] API Style Guide and API Catalog are published in `docs/cross-cutting/api/`
