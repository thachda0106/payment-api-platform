# API Style Guide — Cross-Cutting Reference

## MoMo-like Payment API Platform

> **Source**: Phase 08 — API Design
> **Purpose**: Authoritative conventions for all API design decisions across the platform
> **Audience**: Backend Engineers, Frontend Engineers, Mobile Engineers, QA
> **Classification**: Internal Use Only

---

## 1. Resource Naming

### 1.1 URL Structure

```
/v{version}/{resource}[/{resource_id}][/{sub_resource}][/{action}]
```

| Rule | Example | Anti-Pattern |
|------|---------|-------------|
| Plural resource nouns | `/v1/payments` | `/v1/payment` |
| Lowercase, hyphen-separated | `/v1/api-keys` | `/v1/apiKeys`, `/v1/API_Keys` |
| Nested sub-resources for ownership | `/v1/payments/{id}/refunds` | `/v1/refunds?payment_id=...` |
| Action as terminal noun, not verb | `/v1/payments/{id}/cancel` | `/v1/cancelPayment` |
| No trailing slash | `/v1/payments` | `/v1/payments/` |
| IDs as path parameters, not query | `/v1/payments/{id}` | `/v1/payments?id=...` |

### 1.2 Resource ID Format

All resource identifiers use a prefix-based naming scheme to enable immediate type recognition:

| Resource | Prefix | Example |
|----------|--------|---------|
| Payment | `pay_` | `pay_xyz987654` |
| Refund | `ref_` | `ref_def456789` |
| Payout | `po_` | `po_ghi789012` |
| Account / Wallet | `acc_` | `acc_001_wallet` |
| Journal Entry | `entry_` | `entry_4fA1bC2dE3` |
| Webhook Event | `evt_` | `evt_aBc123DeF456` |
| API Key | `sk_live_` / `sk_test_` | `sk_live_aBc123` |
| Request ID | `req_` | `req_aBcDeFgHiJkL` |

ID body: alphanumeric characters only (`[a-zA-Z0-9]+`).

---

## 2. HTTP Methods

| Method | Semantics | Idempotency | Body |
|--------|-----------|-------------|------|
| `GET` | Retrieve resource(s) | Always idempotent | No body |
| `POST` | Create resource | Requires `Idempotency-Key` | Required |
| `PATCH` | Partial update | Requires `Idempotency-Key` | Required |
| `DELETE` | Remove resource | Requires `Idempotency-Key` | No body |

- **Do NOT use `PUT`.** `PATCH` with partial merge semantics covers all update use cases.
- **Do NOT use `POST` for queries.** Queries with complex filters use `GET` with query parameters.
- **Do NOT use RPC-style verbs** in paths (e.g., `/execute`, `/process`, `/validate`). Use resource nouns and standard methods.

---

## 3. Header Conventions

### 3.1 Required Headers

| Header | When Required | Format | Example |
|--------|---------------|--------|---------|
| `Authorization` | All authenticated endpoints | `Bearer {token}` | `Bearer sk_live_aBc123...` |
| `Content-Type` | `POST`, `PATCH` | `application/json` | — |
| `Idempotency-Key` | `POST`, `PATCH`, `DELETE` | UUID v4 or alphanumeric ≤ 255 chars | `cb174dc0-2ed2-4b2a-bf35-a131015fc65e` |
| `Accept` | All (defaults to JSON) | `application/json` | — |

### 3.2 Optional Headers

| Header | Purpose | Format | Example |
|--------|---------|--------|---------|
| `API-Version` | Request specific API version | `YYYY-MM-DD` | `2026-05-20` |
| `Accept-Encoding` | Response compression | `gzip` | `gzip` |

### 3.3 Response Headers (Server → Client)

| Header | Always Present? | Purpose |
|--------|:--:|---------|
| `Content-Type` | ✅ | `application/json` |
| `API-Version` | ✅ | Version that handled the request |
| `X-Request-Id` | ✅ | Unique request UUID for tracing |
| `X-RateLimit-Limit` | ✅ | Rate limit ceiling |
| `X-RateLimit-Remaining` | ✅ | Requests remaining |
| `X-RateLimit-Reset` | ✅ | Window reset timestamp (Unix seconds) |
| `Idempotent-Replayed` | On replay only | `true` when serving a cached idempotent response |
| `Retry-After` | On 429 only | Seconds until next request allowed |
| `Sunset` | On deprecated endpoints | RFC 8594 sunset date |
| `Deprecation` | On deprecated endpoints | `true` |
| `Link` | On paginated lists | RFC 5988 pagination links |

---

## 4. JSON Conventions

### 4.1 Property Naming

| Rule | Example |
|------|---------|
| `snake_case` for all property names | `source_account_id`, `created_at` |
| No abbreviations unless universally recognized | `id` ✅, `amt` ❌, `src_acct` ❌ |
| Boolean properties: no `is_` prefix | `livemode` ✅, `is_live` ❌ |
| Enum values: lowercase with underscores | `"insufficient_funds"` ✅, `"InsufficientFunds"` ❌ |

### 4.2 Date & Time

| Rule | Format |
|------|--------|
| Timestamps in API responses | Unix epoch **seconds** (integer) |
| Timestamps in API requests | Unix epoch **seconds** (integer) |
| No ISO 8601 strings in API payloads | ❌ `"2026-05-20T10:30:00Z"` |
| Relative durations | Milliseconds (integer) |

**Rationale**: Unix epoch integers avoid timezone parsing ambiguity and are trivially sortable.

### 4.3 Monetary Amounts

| Rule | Format |
|------|--------|
| All amounts in **minor currency unit** | VND: 15000 = 15,000 VND. USD: 1500 = $15.00. |
| `currency` field required alongside `amount` | ISO 4217 3-letter code: `VND`, `USD`, `EUR` |
| Amount is always a **positive integer** | Reversal logic uses `entry_type` (DEBIT/CREDIT), not negative amounts |

### 4.4 Null vs. Absent

| Scenario | Convention |
|----------|-----------|
| Field has no value | Omit the field from the response |
| Field is explicitly null (cleared) | Include `"field": null` (rare, used in PATCH to clear) |
| Boolean default | `false` — do not use `null` for booleans |

---

## 5. Error Response Format

### 5.1 Envelope

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

### 5.2 Error Type to HTTP Code Mapping

| `error.type` | HTTP Status |
|-------------|-------------|
| `api_error` | 500, 503 |
| `authentication_error` | 401 |
| `authorization_error` | 403 |
| `invalid_request_error` | 400, 404, 409, 422 |
| `rate_limit_error` | 429 |
| `idempotency_error` | 400, 409 |

### 5.3 Error Code Naming

- Lowercase with underscores: `insufficient_funds`, `payment_not_found`
- Stable across versions — never rename an existing code
- New codes are additive and backward-compatible

---

## 6. Pagination

### 6.1 Standard

All list endpoints use **cursor-based pagination** with these parameters:

| Parameter | Type | Default | Max |
|-----------|------|---------|-----|
| `limit` | Integer | 20 | 100 |
| `cursor` | String | `null` (first page) | — |

### 6.2 Response Envelope

```json
{
  "object": "list",
  "data": [ ... ],
  "has_more": true,
  "next_cursor": "cUR_Xp9k2MzQa",
  "url": "/v1/payments?limit=20&cursor=cUR_Xp9k2MzQa"
}
```

### 6.3 Cursor Semantics

- Cursors are **opaque strings** — clients must not parse or construct them.
- Cursors encode position in the underlying sequence (`account_sequence` or `created_at` + `id`).
- Cursor format may change between versions without notice.

---

## 7. Versioning

### 7.1 URI Versioning

- Breaking structural changes → new URI version: `/v1/` → `/v2/`
- URI version is the **primary** versioning mechanism
- Multiple URI versions may be served concurrently (v1 + v2)

### 7.2 Header Versioning

- `API-Version: YYYY-MM-DD` for additive, backward-compatible changes within the same URI version
- Defaults to latest if omitted
- Response always returns the version that handled the request

### 7.3 Deprecation

- `Sunset` header (RFC 8594) gives the date of removal
- Minimum 12-month notice for breaking changes
- Emails to registered consumers at 12, 6, 3, and 1 month before sunset

---

## 8. Idempotency

### 8.1 Header

```
Idempotency-Key: cb174dc0-2ed2-4b2a-bf35-a131015fc65e
```

### 8.2 Rules

- **Required**: `POST`, `PATCH`, `DELETE`
- **Optional (ignored)**: `GET`
- **Key format**: UUID v4 recommended. Any string ≤ 255 chars accepted.
- **Scope**: Per API key. Not globally unique.
- **TTL**: 24 hours from last use.
- **Replay**: Same key + same body → original response + `Idempotent-Replayed: true`
- **Mismatch**: Same key + different body → `409 Conflict — idempotency_key_mismatch`

---

## 9. Rate Limiting

### 9.1 Tiers

| Tier | Limit | Burst |
|------|-------|-------|
| Public (Unauthenticated) | 5 req/s | +3 |
| Authenticated | 20 req/s | +10 |
| Admin | 50 req/s | +25 |

### 9.2 Headers

```
X-RateLimit-Limit: 20
X-RateLimit-Remaining: 15
X-RateLimit-Reset: 1713024060
Retry-After: 12  (429 only)
```

### 9.3 Client Behavior

- Always check `X-RateLimit-Remaining` before sending requests
- On 429: wait `Retry-After` seconds + jitter before retrying
- Use exponential backoff jitter for retries (not just `Retry-After`)

---

## 10. Authentication

### 10.1 Schemes

| Scheme | Header | Use Case |
|--------|--------|----------|
| Bearer JWT | `Authorization: Bearer eyJhbG...` | End-user / merchant sessions (OAuth 2.0) |
| Bearer API Key | `Authorization: Bearer sk_live_...` | Server-to-server, partners |
| mTLS | TLS client cert (SPIFFE ID) | Internal service-to-service |

### 10.2 API Key Prefixes

| Prefix | Environment | Example |
|--------|------------|---------|
| `sk_live_` | Production | `sk_live_aBc123` |
| `sk_test_` | Sandbox | `sk_test_xYz789` |

---

## 11. CORS Policy

- `Access-Control-Allow-Origin`: Restricted to registered origins (configured per environment)
- `Access-Control-Allow-Methods`: `GET, POST, PATCH, DELETE, OPTIONS`
- `Access-Control-Allow-Headers`: `Authorization, Content-Type, Idempotency-Key, API-Version`
- `Access-Control-Max-Age`: `86400` (24 hours)
- Wildcard origins (`*`) are **never allowed** in production

---

## 12. Security Headers

All responses from the platform edge must include:

```
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Cache-Control: no-store (transactional endpoints)
Cache-Control: public, max-age=30 (read-only endpoints, where applicable)
```

---

## 13. Checklist: Adding a New Endpoint

Before opening a PR for a new endpoint, verify:

- [ ] URL follows resource naming conventions (§1)
- [ ] HTTP method matches semantics (§2)
- [ ] `Idempotency-Key` required on `POST`/`PATCH`/`DELETE` (§8)
- [ ] Request/response schemas use `snake_case` (§4.1)
- [ ] Monetary amounts in minor currency unit with `currency` field (§4.3)
- [ ] Timestamps are Unix epoch seconds (§4.2)
- [ ] Error responses use the standard envelope (§5.1)
- [ ] List endpoints use cursor-based pagination (§6)
- [ ] Auth scope declared and enforced (§10)
- [ ] OpenAPI spec file updated in `docs/cross-cutting/api/specs/`
- [ ] `api-catalog.md` updated with new endpoint
- [ ] Breaking changes: `Sunset` header added with 12-month notice (§7.3)
- [ ] OpenAPI spec passes `spectral` lint in CI
