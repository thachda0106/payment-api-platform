# API Catalog — Cross-Cutting Index

## MoMo-like Payment API Platform

> **Source**: Phase 08 — API Design (v3.0)
> **Purpose**: Single-source index of all API endpoints, auth requirements, and OpenAPI spec locations
> **Audience**: All engineering teams
> **Last Updated**: 2026-05-20

---

## Endpoint Index by Resource

### Payments

| Endpoint | Method | Scope | Spec File |
|----------|--------|-------|-----------|
| Create Payment | `POST /v1/payments` | `write:payments` | `payments-api.yaml` |
| List Payments | `GET /v1/payments` | `read:payments` | `payments-api.yaml` |
| Retrieve Payment | `GET /v1/payments/{id}` | `read:payments` | `payments-api.yaml` |
| Update Payment Metadata | `PATCH /v1/payments/{id}` | `write:payments` | `payments-api.yaml` |
| Cancel Payment | `POST /v1/payments/{id}/cancel` | `write:payments` | `payments-api.yaml` |
| Create Refund | `POST /v1/payments/{id}/refunds` | `write:refunds` | `payments-api.yaml` |

### Refunds

| Endpoint | Method | Scope | Spec File |
|----------|--------|-------|-----------|
| Retrieve Refund | `GET /v1/refunds/{id}` | `read:refunds` | `refunds-payouts-api.yaml` 🚧 |
| List Refunds | `GET /v1/refunds` | `read:refunds` | `refunds-payouts-api.yaml` 🚧 |

### Wallets

| Endpoint | Method | Scope | Spec File |
|----------|--------|-------|-----------|
| Get Wallet Balances | `GET /v1/wallets/balances` | `read:wallets` | `wallets-api.yaml` 🚧 |
| Get Single Balance | `GET /v1/wallets/balances/{currency}` | `read:wallets` | `wallets-api.yaml` 🚧 |
| Top-Up Wallet | `POST /v1/wallets/top-up` | `write:wallets` | `wallets-api.yaml` 🚧 |
| Withdraw | `POST /v1/wallets/withdraw` | `write:wallets` | `wallets-api.yaml` 🚧 |
| Get Wallet Details | `GET /v1/wallets` | `read:wallets` | `wallets-api.yaml` 🚧 |
| Freeze Wallet | `POST /v1/wallets/{id}/freeze` | `admin:wallets` | `wallets-api.yaml` 🚧 |
| Unfreeze Wallet | `POST /v1/wallets/{id}/unfreeze` | `admin:wallets` | `wallets-api.yaml` 🚧 |

### Transactions

| Endpoint | Method | Scope | Spec File |
|----------|--------|-------|-----------|
| List Transactions | `GET /v1/transactions` | `read:transactions` | `transactions-api.yaml` 🚧 |
| Retrieve Transaction | `GET /v1/transactions/{id}` | `read:transactions` | `transactions-api.yaml` 🚧 |

### Payouts

| Endpoint | Method | Scope | Spec File |
|----------|--------|-------|-----------|
| Create Payout | `POST /v1/payouts` | `write:payouts` | `refunds-payouts-api.yaml` 🚧 |
| Retrieve Payout | `GET /v1/payouts/{id}` | `read:payouts` | `refunds-payouts-api.yaml` 🚧 |
| List Payouts | `GET /v1/payouts` | `read:payouts` | `refunds-payouts-api.yaml` 🚧 |
| Cancel Payout | `POST /v1/payouts/{id}/cancel` | `write:payouts` | `refunds-payouts-api.yaml` 🚧 |

### Webhooks

| Endpoint | Method | Scope | Spec File |
|----------|--------|-------|-----------|
| Register Endpoint | `POST /v1/webhooks/endpoints` | `admin:webhooks` | `webhooks-api.yaml` 🚧 |
| List Endpoints | `GET /v1/webhooks/endpoints` | `admin:webhooks` | `webhooks-api.yaml` 🚧 |
| Retrieve Endpoint | `GET /v1/webhooks/endpoints/{id}` | `admin:webhooks` | `webhooks-api.yaml` 🚧 |
| Update Endpoint | `PATCH /v1/webhooks/endpoints/{id}` | `admin:webhooks` | `webhooks-api.yaml` 🚧 |
| Delete Endpoint | `DELETE /v1/webhooks/endpoints/{id}` | `admin:webhooks` | `webhooks-api.yaml` 🚧 |
| Rotate Secret | `POST /v1/webhooks/endpoints/{id}/rotate-secret` | `admin:webhooks` | `webhooks-api.yaml` 🚧 |
| List Deliveries | `GET /v1/webhooks/endpoints/{id}/deliveries` | `admin:webhooks` | `webhooks-api.yaml` 🚧 |
| Retry Delivery | `POST /v1/webhooks/endpoints/{id}/deliveries/{did}/retry` | `admin:webhooks` | `webhooks-api.yaml` 🚧 |

### Admin / Platform

| Endpoint | Method | Scope | Spec File |
|----------|--------|-------|-----------|
| Health Check | `GET /v1/health` | Public | `admin-api.yaml` 🚧 |
| API Status | `GET /v1/status` | Public | `admin-api.yaml` 🚧 |
| List API Keys | `GET /v1/api-keys` | `admin:platform` | `admin-api.yaml` 🚧 |
| Create API Key | `POST /v1/api-keys` | `admin:platform` | `admin-api.yaml` 🚧 |
| Revoke API Key | `DELETE /v1/api-keys/{id}` | `admin:platform` | `admin-api.yaml` 🚧 |
| Rate Limit Config | `GET /v1/admin/rate-limits` | `admin:platform` | `admin-api.yaml` 🚧 |

---

## Auth Scope Taxonomy

| Scope | Resource Access | Assigned Roles |
|-------|----------------|---------------|
| `read:payments` | View payment details and lists | USER, MERCHANT, SUPPORT_AGENT |
| `write:payments` | Create, update, cancel payments | USER, MERCHANT |
| `read:refunds` | View refund details and lists | USER, MERCHANT, SUPPORT_AGENT |
| `write:refunds` | Create refunds | MERCHANT, SUPPORT_LEAD, FINANCE_ADMIN |
| `read:wallets` | View wallet balances and details | USER, MERCHANT |
| `write:wallets` | Top-up, withdraw | USER, MERCHANT |
| `admin:wallets` | Freeze/unfreeze wallets | SUPPORT_LEAD, FINANCE_ADMIN, RISK_ANALYST |
| `read:transactions` | View transaction history | USER, MERCHANT, SUPPORT_AGENT |
| `read:payouts` | View payout details | MERCHANT |
| `write:payouts` | Create payouts | MERCHANT |
| `admin:webhooks` | Manage webhook endpoints | MERCHANT, PLATFORM_ADMIN |
| `admin:platform` | API key management, rate limit config | PLATFORM_ADMIN, SUPER_ADMIN |

---

## Spec File Status

| Spec File | Status | Endpoints Covered |
|-----------|--------|------------------|
| `payments-api.yaml` | ✅ Complete | 6 endpoints (Payments + Refunds.create) |
| `wallets-api.yaml` | 🚧 Pending | 7 endpoints |
| `refunds-payouts-api.yaml` | 🚧 Pending | 6 endpoints (2 refunds + 4 payouts) |
| `transactions-api.yaml` | 🚧 Pending | 2 endpoints |
| `webhooks-api.yaml` | 🚧 Pending | 8 endpoints |
| `auth-api.yaml` | 🚧 Pending | Auth, register, token refresh |
| `admin-api.yaml` | 🚧 Pending | 6 endpoints |

---

## Cross-Cutting Standards

| Concern | Standard | Reference |
|---------|----------|-----------|
| Resource naming | Plural, snake_case, prefix-based IDs | [API Style Guide §1](api-style-guide.md) |
| JSON conventions | snake_case properties, Unix timestamps, minor currency units | [API Style Guide §4](api-style-guide.md) |
| Error envelope | Stripe-compatible: `{error: {type, code, message, param, doc_url}}` | [API Style Guide §5](api-style-guide.md) |
| Pagination | Cursor-based, `limit` + `cursor`, standard list envelope | [API Style Guide §6](api-style-guide.md) |
| Idempotency | `Idempotency-Key` header, 24h TTL, per API key scope | [API Style Guide §8](api-style-guide.md) |
| Rate limiting | Token bucket, `X-RateLimit-*` headers, 5/20/50 req/s tiers | [API Style Guide §9](api-style-guide.md) |
| Versioning | URI (`/v1/`) + header (`API-Version`) dual-layer | [API Style Guide §7](api-style-guide.md) |
| Deprecation | `Sunset` header, 12-month minimum notice | [API Style Guide §7.3](api-style-guide.md) |
