# Module 01 — Relational Theory & SQL Mastery

## 1.1 The Relational Model

The relational model, invented by E.F. Codd in 1970, represents data as **relations** (tables) of **tuples** (rows) with **attributes** (columns). This is the theoretical foundation of every SQL database.

### Relations

A relation is a set of tuples. Key properties:
- **No duplicate tuples** (enforced by PRIMARY KEY)
- **No ordering** (tuples are unordered — ORDER BY is for display only)
- **Atomic values** (each cell contains a single value — 1NF)

### Keys

| Key Type | Definition | Example |
|----------|-----------|---------|
| **Superkey** | Any set of attributes that uniquely identifies a tuple | `{account_id, created_at}` |
| **Candidate Key** | Minimal superkey (no subset is a superkey) | `{account_id}`, `{email}` |
| **Primary Key** | The chosen candidate key | `account_id UUID PRIMARY KEY` |
| **Foreign Key** | Attribute referencing a primary key in another relation | `user_id UUID REFERENCES users(id)` |
| **Composite Key** | Primary key composed of multiple attributes | `(order_id, line_number)` |

### Integrity Constraints

```sql
CREATE TABLE wallet_balances (
    account_id    UUID PRIMARY KEY,                          -- Entity integrity
    currency      CHAR(3) NOT NULL DEFAULT 'VND',            -- Domain constraint
    balance       BIGINT NOT NULL CHECK (balance >= 0),      -- Domain constraint (no negative)
    version       INT NOT NULL DEFAULT 1,
    user_id       UUID NOT NULL REFERENCES users(id),        -- Referential integrity
    UNIQUE (user_id, currency)                                -- Business rule: one row per user per currency
);
```

---

## 1.2 Normalization

Normalization eliminates data redundancy and anomalies. Each normal form adds a constraint.

### 1NF — Atomic Values

Every column contains a SINGLE value. No arrays, no comma-separated lists.

```
❌ BAD: payment_methods = "card,bank,wallet"   (comma-separated)
✅ GOOD: Separate rows or separate table
```

### 2NF — No Partial Dependencies

Every non-key column depends on the WHOLE primary key, not just part of it. Relevant only for composite keys.

```
❌ BAD: order_items(order_id, product_id, product_name, quantity)
        product_name depends only on product_id (part of the key), not on the full key
✅ GOOD: orders(order_id, ...) + products(product_id, product_name) + order_items(order_id, product_id, quantity)
```

### 3NF — No Transitive Dependencies

Every non-key column depends on the key, the whole key, and nothing but the key.

```
❌ BAD: payments(payment_id, merchant_id, merchant_name, merchant_tier)
        merchant_name and merchant_tier depend on merchant_id, not on payment_id
✅ GOOD: payments(payment_id, merchant_id, ...) + merchants(merchant_id, name, tier)
```

### BCNF — Boyce-Codd Normal Form

Stricter than 3NF. Every determinant must be a candidate key.

**When to denormalize**: For read performance. The `wallet_balances` table is a denormalized projection of `journal_lines`. It violates normalization (balance can be computed from journal lines), but it's a necessary performance optimization.

---

## 1.3 SQL — Data Definition Language (DDL)

### Data Types for Financial Systems

| Type | Use | Why |
|------|-----|-----|
| `UUID` | Primary keys, idempotency keys | Globally unique, no sequential guessing |
| `BIGINT` | Amounts in smallest currency unit (cents, xu) | Exact integer arithmetic, no rounding. Max: ~9.2 × 10^18 |
| `NUMERIC(precision, scale)` | Amounts with exact decimal | For currencies where BIGINT ranges aren't enough |
| `CHAR(3)` | Currency codes | Fixed-length, fast comparison |
| `VARCHAR(N)` | Names, descriptions | Variable-length text |
| `TEXT` | Unbounded text | JSON payloads, free-form notes |
| `TIMESTAMPTZ` | Timestamps with timezone | ALWAYS use TIMESTAMPTZ (stores UTC, displays in session timezone) |
| `BOOLEAN` | Flags | `is_active`, `processed` |
| `JSONB` | Semi-structured data | Event payloads, flexible metadata |

### Table Creation with Financial Patterns

```sql
CREATE TABLE journal_entries (
    entry_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_id      UUID NOT NULL,              -- Groups related entries (e.g., one payment = multiple journal entries)
    reference_type  VARCHAR(50) NOT NULL,        -- PAYMENT, TRANSFER, REFUND, FEE
    reference_id    UUID NOT NULL,               -- ID of the business entity this entry relates to
    description     VARCHAR(500),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE, -- Prevent duplicate processing
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(100) NOT NULL        -- user_id or system
);

CREATE TABLE journal_lines (
    line_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id        UUID NOT NULL REFERENCES journal_entries(entry_id),
    account_id      VARCHAR(255) NOT NULL,       -- Chart of accounts reference (e.g., "liability:user_wallet:U1")
    entry_type      VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount          BIGINT NOT NULL CHECK (amount > 0),
    currency        CHAR(3) NOT NULL DEFAULT 'VND',
    line_order      INT NOT NULL DEFAULT 1       -- Ordering within a journal entry
);

CREATE TABLE wallet_balances (
    account_id       VARCHAR(255) NOT NULL,
    currency         CHAR(3) NOT NULL DEFAULT 'VND',
    available_balance BIGINT NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    pending_balance  BIGINT NOT NULL DEFAULT 0 CHECK (pending_balance >= 0),
    frozen_balance   BIGINT NOT NULL DEFAULT 0 CHECK (frozen_balance >= 0),
    version          INT NOT NULL DEFAULT 1,     -- Optimistic concurrency
    PRIMARY KEY (account_id, currency)
);

CREATE TABLE idempotency_keys (
    api_key     VARCHAR(100) NOT NULL,
    key         VARCHAR(255) NOT NULL,
    response    JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (api_key, key)
);

-- Index for TTL cleanup
CREATE INDEX idx_idempotency_created ON idempotency_keys(created_at)
    WHERE created_at < NOW() - INTERVAL '24 hours';
```

---

## 1.4 SQL — Data Manipulation Language (DML)

### SELECT — The Most Important Statement

```sql
-- Basic SELECT with filtering and ordering
SELECT account_id, available_balance, currency
FROM wallet_balances
WHERE available_balance > 100000
  AND currency = 'VND'
ORDER BY available_balance DESC
LIMIT 20;

-- NEVER use FLOAT for money. Use BIGINT (cents) or NUMERIC.
-- Display amounts: amount / 1.0 for VND, amount / 100.0 for USD cents
SELECT account_id, available_balance / 1.0 AS balance_vnd
FROM wallet_balances;
```

### Aggregation

```sql
-- Total per currency
SELECT currency, SUM(available_balance) AS total_balance, COUNT(*) AS accounts
FROM wallet_balances
GROUP BY currency;

-- Filter groups with HAVING (applied AFTER GROUP BY)
SELECT account_id, SUM(amount) AS total_debits
FROM journal_lines
WHERE entry_type = 'DEBIT'
  AND created_at >= NOW() - INTERVAL '30 days'
GROUP BY account_id
HAVING SUM(amount) > 1000000
ORDER BY total_debits DESC;
```

### INSERT

```sql
INSERT INTO journal_entries (journal_id, reference_type, reference_id, description, idempotency_key, created_by)
VALUES ('550e8400-...', 'PAYMENT', '660e8400-...', 'Payment P2P U1→U2', 'unique-key-123', 'user-1');

-- Multi-row INSERT (lines for a journal entry)
INSERT INTO journal_lines (entry_id, account_id, entry_type, amount, currency, line_order) VALUES
    ('770e8400-...', 'liability:user_wallet:U1', 'DEBIT',  100000, 'VND', 1),
    ('770e8400-...', 'liability:user_wallet:U2', 'CREDIT',  98500, 'VND', 2),
    ('770e8400-...', 'revenue:platform_fee',     'CREDIT',   1500, 'VND', 3);
```

### UPDATE with Locking

```sql
-- Atomic debit with balance check
UPDATE wallet_balances
SET available_balance = available_balance - 10000,
    version = version + 1
WHERE account_id = 'U1'
  AND currency = 'VND'
  AND available_balance >= 10000;  -- Check constraint in WHERE

-- If 0 rows updated → insufficient balance
-- If 1 row updated → success
```

### DELETE

```sql
-- NEVER hard-delete financial records. Use soft delete or archive.
-- Instead of: DELETE FROM journal_entries WHERE entry_id = '...';
-- Audit trail must be preserved.

-- For cleanup: delete expired idempotency keys
DELETE FROM idempotency_keys
WHERE created_at < NOW() - INTERVAL '24 hours';
```

---

## 1.5 JOINs

### Types

```sql
-- INNER JOIN: Only matching rows
SELECT p.payment_id, u.name AS user_name, m.name AS merchant_name
FROM payments p
INNER JOIN users u ON p.user_id = u.id
INNER JOIN merchants m ON p.merchant_id = m.id;

-- LEFT JOIN: All left rows + matching right (NULL if no match)
SELECT u.name, p.payment_id
FROM users u
LEFT JOIN payments p ON u.id = p.user_id;  -- Users with no payments still appear

-- FULL OUTER JOIN: All rows from both sides
-- Rarely used. Mostly for reconciliation: find entries in one system but not the other
SELECT COALESCE(l.account_id, w.account_id) AS account_id,
       l.ledger_balance, w.wallet_balance
FROM ledger_balance_view l
FULL OUTER JOIN wallet_balances w ON l.account_id = w.account_id
WHERE l.ledger_balance IS DISTINCT FROM w.wallet_balance;  -- Find discrepancies

-- CROSS JOIN: Cartesian product (all combinations). Useful with generate_series
SELECT d.date, h.hour
FROM generate_series('2026-01-01'::date, '2026-01-07'::date, '1 day') d
CROSS JOIN generate_series(0, 23) h;  -- Every hour of every day
```

### Join Strategies (What PostgreSQL Does Internally)

| Strategy | When Used | Performance |
|----------|-----------|------------|
| **Nested Loop** | Small outer table + indexed inner table | Good for selective queries |
| **Hash Join** | Large tables, no useful index | Builds hash table of inner in memory. Spills to disk if > work_mem |
| **Merge Join** | Both tables sorted on join key | Both inputs must be sorted (or index scan provides ordering) |

You'll see which strategy was used in EXPLAIN output — see Module 03.

---

## 1.6 Subqueries & CTEs

### Subqueries

```sql
-- Subquery in WHERE: find users who spent more than average
SELECT user_id, SUM(amount) AS total_spent
FROM payments
WHERE status = 'COMPLETED'
GROUP BY user_id
HAVING SUM(amount) > (SELECT AVG(user_total) FROM (
    SELECT SUM(amount) AS user_total FROM payments WHERE status = 'COMPLETED' GROUP BY user_id
) sub);

-- Subquery in SELECT (correlated — runs once per row)
SELECT user_id, (SELECT COUNT(*) FROM payments p2 WHERE p2.user_id = u.id) AS payment_count
FROM users u;

-- EXISTS: efficiently check for existence
SELECT * FROM users u
WHERE EXISTS (SELECT 1 FROM payments p WHERE p.user_id = u.id AND p.status = 'COMPLETED');
```

### CTEs (Common Table Expressions)

```sql
-- CTE: better readability, reusable within query
WITH user_totals AS (
    SELECT user_id, SUM(amount) AS total_spent, COUNT(*) AS txn_count
    FROM payments WHERE status = 'COMPLETED'
    GROUP BY user_id
),
avg_spent AS (
    SELECT AVG(total_spent) AS avg_amount FROM user_totals
)
SELECT u.name, ut.total_spent, ut.txn_count,
       CASE WHEN ut.total_spent > a.avg_amount THEN 'HIGH' ELSE 'LOW' END AS segment
FROM user_totals ut
JOIN users u ON u.id = ut.user_id
CROSS JOIN avg_spent a
ORDER BY ut.total_spent DESC;

-- Recursive CTE: traverse hierarchical data (e.g., chart of accounts tree)
WITH RECURSIVE account_tree AS (
    SELECT account_id, parent_id, name, 1 AS level
    FROM ledger_accounts WHERE parent_id IS NULL
    UNION ALL
    SELECT la.account_id, la.parent_id, la.name, at.level + 1
    FROM ledger_accounts la
    INNER JOIN account_tree at ON la.parent_id = at.account_id
)
SELECT * FROM account_tree ORDER BY level, name;
```

### Window Functions

Window functions compute values across a "window" of rows without collapsing them (unlike GROUP BY).

```sql
-- ROW_NUMBER: assign sequential numbers
SELECT payment_id, amount, created_at,
       ROW_NUMBER() OVER (ORDER BY created_at) AS rn
FROM payments;

-- RANK vs DENSE_RANK: handle ties differently
SELECT user_id, SUM(amount) AS total,
       RANK() OVER (ORDER BY SUM(amount) DESC) AS rank,        -- 1,2,2,4 (gap after tie)
       DENSE_RANK() OVER (ORDER BY SUM(amount) DESC) AS dense  -- 1,2,2,3 (no gap)
FROM payments GROUP BY user_id;

-- LAG / LEAD: access previous/next row
SELECT created_at, amount,
       LAG(amount) OVER (ORDER BY created_at) AS prev_amount,
       amount - LAG(amount) OVER (ORDER BY created_at) AS diff
FROM payments WHERE user_id = 'U1';

-- Running total with frame
SELECT created_at, amount,
       SUM(amount) OVER (ORDER BY created_at
           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_total
FROM payments WHERE user_id = 'U1';

-- PARTITION BY: window per group
SELECT user_id, payment_id, amount, created_at,
       ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at DESC) AS user_txn_seq
FROM payments;
```

---

## 1.7 Exercises

### Ex 1.1 — Schema Design
Given the payment platform domain, design tables for: `users`, `merchants`, `payments`, `refunds`. Include all constraints (PK, FK, UNIQUE, CHECK, NOT NULL). Choose appropriate data types. Write the CREATE TABLE statements.

### Ex 1.2 — Complex Query
Write a query that produces a daily settlement report: date, merchant_name, total_payments, total_refunds, net_amount, transaction_count. Use JOINs, GROUP BY, and appropriate date filtering.

### Ex 1.3 — Window Functions
Write queries using: (a) ROW_NUMBER to find each user's most recent payment, (b) LAG to calculate time between consecutive payments for a user, (c) running total of daily payment volume.

### Ex 1.4 — CTE
Use a recursive CTE to traverse a chart of accounts tree. Compute the total balance for each account including all sub-accounts.

### Ex 1.5 — Reconciliation Query
Using FULL OUTER JOIN, find all accounts where the ledger balance differs from the wallet balance.

---

## 1.8 Self-Assessment

- [ ] Can design a normalized (3NF) schema from a domain description
- [ ] Can write a query with INNER JOIN, LEFT JOIN, subquery, CTE, and window function
- [ ] Understand when to use GROUP BY + HAVING vs. window functions
- [ ] Know the difference between RANK, DENSE_RANK, and ROW_NUMBER
- [ ] Can explain why BIGINT (cents) is used for money, not FLOAT
- [ ] Understand the three join strategies and when each is used
- [ ] Can write a recursive CTE for hierarchical data
