# Cross-Cutting Data Architecture

> **Note**: This document is auto-generated as part of Phase 07 (Data Architecture).
> **Primary Source of Truth**: [`docs/stages/B-domain-architecture/07-data-architecture.md`](../../stages/B-domain-architecture/07-data-architecture.md)

## Summary

This cross-cutting view synthesizes the comprehensive data models, storage strategy, and infrastructure from the Phase 07 architecture design.

### Storage Infrastructure

| Technology | Purpose | Contexts |
| :--- | :--- | :--- |
| **PostgreSQL** | Primary Relational Store | Financial Core, Payment, Identity, Merchant, FX |
| **Redis** | Volatile Cache & Fast Locking | Risk & Fraud, Idempotency Caches |
| **OpenSearch** | Full-text, Cross-domain Search | CQRS Read Models |
| **TimescaleDB** | Immutable Time-series | Audit |
| **S3** | Deep Archive | Cold Storage (T > 365 Days) |

### Key Constraints enforced system-wide

1. **Strict DB Boundaries**: No cross-service SQL joins; enforce isolation.
2. **Pessimistic Locking**: `SELECT FOR UPDATE NOWAIT` exclusively for financial transactions.
3. **Partitioning**: Hot databases enforce partitioned structures (`HASH(key) + RANGE(created_at)`) for IOPS health. 
4. **Idempotency keys**: Mandatory at both the application and Database Unique Constraint level for all mutating requests.
