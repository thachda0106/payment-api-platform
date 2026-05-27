# Module 02 — Financial Accounting for Engineers

## Duration: 3–4 hours | Critical: Yes

---

## Learning Objectives

By the end of this module, you will understand:
- The accounting equation: Assets = Liabilities + Equity
- Double-entry bookkeeping: why every transaction has a debit AND a credit
- Chart of accounts: how financial transactions are categorized
- Journal entries, general ledger, and trial balance
- How payment platforms use accounting internally
- Why "balance = sum of all debits — sum of all credits since inception" is wrong (you need double-entry)

---

## 1. The Accounting Equation

```
Assets = Liabilities + Equity
```

This is the foundation of every financial system ever built. Every transaction must preserve this equation.

### In the Context of a Payment Platform

| Account | Type | Example |
|---------|------|---------|
| Customer A's wallet balance | **Liability** | MoMo owes A 1,000,000 VND |
| Customer B's wallet balance | **Liability** | MoMo owes B 500,000 VND |
| MoMo's bank account | **Asset** | MoMo's own money at Vietcombank |
| Merchant's settlement account | **Liability** | MoMo owes merchant 200,000 VND |
| Platform fee revenue | **Equity** | MoMo's earnings from transaction fees |

**Critical insight**: When a customer "has 1,000,000 VND in their MoMo wallet," that's NOT MoMo's money. It's a **liability** — MoMo owes that money to the customer. MoMo can only earn money through fees (which go to Equity).

---

## 2. Double-Entry Bookkeeping

### The Cardinal Rule

> Every financial transaction is recorded as **at least two entries**: a **debit** on one account and a **credit** on another.
> **Debits must always equal Credits.**

### Debit vs. Credit — The Confusion

This is the #1 source of confusion for engineers. Forget the banking terms "credit card" and "debit card." The accounting meanings are different:

| Action | Asset Account | Liability Account | Equity Account |
|--------|:---:|:---:|:---:|
| **Debit (DR)** | Increases (+) | Decreases (−) | Decreases (−) |
| **Credit (CR)** | Decreases (−) | Increases (+) | Increases (+) |

**Mnemonic**: **DEAL** — Debit Expires Assets, Liabilities grow. Or simply: think of it from the company's perspective, not the customer's.

### Worked Example: User Top-Up

```
User transfers 100,000 VND from their bank to their MoMo wallet.

Entry 1: Bank Account (Asset) side
  Debit:  Bank_Vietcombank           100,000 VND  ↑ (Asset increases — we have more money)
  Credit: Liability_Wallet_User_A     100,000 VND  ↑ (Liability increases — we owe more to user)

Net effect: Assets ↑ 100K, Liabilities ↑ 100K. Equation preserved.
```

### Worked Example: User Pays Merchant

```
User A pays User B (merchant) 50,000 VND.
Platform fee: 1,000 VND.

Entry:
  Debit:  Liability_Wallet_User_A    51,000 VND  ↓ (Liability decreases — we owe A less)
  Credit: Liability_Wallet_User_B    50,000 VND  ↑ (Liability increases — we owe B more)
  Credit: Revenue_Platform_Fee        1,000 VND  ↑ (Equity increases — platform earned)

Net effect: Liabilities ↓ A, ↑ B (net ↓ 1K), Equity ↑ 1K. Equation preserved.
```

Notice: every entry involves exactly two sides, but can involve MORE than two accounts (compound journal entry). The sum of all debits must still equal the sum of all credits.

---

## 3. Chart of Accounts

A chart of accounts is the structured list of all financial accounts in the system. It's hierarchical.

### Simplified Payment Platform CoA

```
1xxx — ASSETS
  1100 — Cash & Bank
    1101 — Vietcombank Operating Account
    1102 — Settlement Account (NAPAS)
    1103 — Reserve Account (SBV)
  1200 — Receivables
    1201 — Pending Settlement from NAPAS
    1202 — Merchant Receivables
  1300 — Crypto Assets (future)

2xxx — LIABILITIES
  2100 — Customer Wallets
    2101 — Individual Wallet - Tier 1
    2102 — Individual Wallet - Tier 2
    2103 — Individual Wallet - Tier 3
    2104 — Merchant Wallet
  2200 — Settlement Liabilities
    2201 — Pending Merchant Settlement
    2202 — Pending Partner Settlement
  2300 — Escrow / Holds
    2301 — Transaction Hold
    2302 — Dispute Escrow

3xxx — EQUITY
  3100 — Paid-in Capital
  3200 — Retained Earnings
  3300 — Revenue
    3301 — Transaction Fee Revenue
    3302 — Merchant Fee Revenue
    3303 — Withdrawal Fee Revenue
    3304 — Interest Income
  3400 — Expenses
    3401 — Payment Network Fees (NAPAS)
    3402 — Bank Transfer Fees
    3403 — Processing Costs
```

---

## 4. Journal Entries, Ledger, and Trial Balance

### Journal Entry

A journal entry is the raw record of a financial event. It is **immutable** — once posted, it can never be deleted or modified (only corrected via reversing entries).

```
Journal Entry #:  JE-20260321-001
Date:             2026-03-21T14:30:00+07:00
Description:      User top-up via Vietcombank
Reference:        TXN-8f7a3b
Status:           POSTED

Lines:
  DR  1101_Vietcombank_Operating        100,000 VND
  CR  2101_Wallet_User_A                100,000 VND
  -----------------------------------------------
  Total DR: 100,000   Total CR: 100,000    ✓ Balanced
```

### General Ledger

The ledger is the accumulated total of ALL journal entries for each account. Think of it as a running balance per account.

```
Account: 2101 — Wallet User A
┌─────┬──────────────┬──────────┬──────────┬──────────┐
│ #   │ Date         │ Debit    │ Credit   │ Balance  │
├─────┼──────────────┼──────────┼──────────┼──────────┤
│ E1  │ 2026-03-01   │          │ 500,000  │ 500,000  │
│ E2  │ 2026-03-05   │ 50,000   │          │ 450,000  │
│ E3  │ 2026-03-10   │ 30,000   │          │ 420,000  │
│ E4  │ 2026-03-15   │          │ 200,000  │ 620,000  │
│ E5  │ 2026-03-21   │          │ 100,000  │ 720,000  │
└─────┴──────────────┴──────────┴──────────┴──────────┘
```

**Critical**: For liability accounts, Credits INCREASE the balance. For asset accounts, Debits INCREASE the balance.

### Trial Balance

A report showing all account balances at a given point. The sum of all debit balances MUST equal the sum of all credit balances.

```
Trial Balance — 2026-03-21
─────────────────────────────────────────────────
Account                          DR           CR
─────────────────────────────────────────────────
1101 Vietcombank Op             500,000,000
1201 Pending Settlement           20,000,000
2101 Customer Wallets                         250,000,000
2104 Merchant Wallets                         180,000,000
3301 Transaction Fees                           15,000,000
3401 NAPAS Fees                   5,000,000
─────────────────────────────────────────────────
Total DR                    525,000,000  ┐
Total CR                                  525,000,000  │ = Balanced ✓
─────────────────────────────────────────────────
```

---

## 5. Payment Platform Accounting in Practice

### 5.1 The Wallet-Ledger Relationship

This is THE most important design decision in a payment platform:

| Approach | Description | Risk |
|----------|-------------|------|
| **Wallet-driven balance** | Balance = computed from wallet table | Single point of failure. A bug in wallet code creates undetectable accounting errors. |
| **Ledger-driven balance** | Balance = computed from journal entries | Immutable audit trail. Every change is recorded. Wallet is just a cached read model. |

**Our platform MUST use ledgers to drive balances (not the other way around).**

```
WRONG:  wallet.balance += 100,000      // Direct mutation — no audit trail
RIGHT:  INSERT INTO journal_entries ... // Immutable log
        wallet.balance = SELECT SUM(credits - debits) FROM ledger WHERE account_id = X
```

### 5.2 Pending vs. Posted Transactions

In a real payment system, not all entries are final immediately:

| State | Meaning | Accounting Treatment |
|-------|---------|---------------------|
| **PENDING** | Transaction initiated but not confirmed | No journal entry yet. Balance reflects hold. |
| **POSTED** | Transaction confirmed and irreversible | Journal entry created. Balance updated. |
| **HELD** | Funds reserved (e.g., auth hold) | Contra-account: `2301 Transaction Hold` |
| **REVERSED** | Hold released, no settlement | Reverse the hold contra-entry |
| **FAILED** | Transaction rejected before posting | No journal entry needed |
| **VOIDED** | Transaction cancelled after posting | Reversing entry required |

### 5.3 The Settlement Process

Settlement is where the platform's internal accounting meets the external banking system:

```
Internal ledger (MoMo):
  Debit: 2101_Wallet_User_A         50,000
  Credit: 2104_Wallet_Merchant_B    50,000

External bank account:
  (No change yet — settlement is end-of-day)

At EOD Settlement:
  Debit: 1101_Vietcombank_Operating  48,000,000  ← Total of all net wallet outflows
  Credit: 2201_Pending_Settlement    48,000,000

When bank confirms settlement:
  Debit: 2201_Pending_Settlement     48,000,000
  Credit: 1101_Vietcombank_Operating  48,000,000  ← Reclassify to clear
  (Settled account reflects actual bank balance)
```

---

## 6. Cryptographic Hash Chaining

To make the ledger truly tamper-proof, we use hash chaining — each journal entry includes a hash of the previous entry:

```
JE #1: DATA + prev_hash = "0000000000000000" → hash = "a1b2c3d4"
JE #2: DATA + prev_hash = "a1b2c3d4"         → hash = "e5f6a7b8"
JE #3: DATA + prev_hash = "e5f6a7b8"         → hash = "9c0d1e2f"
```

If someone modifies JE #2, the hash chain breaks at JE #3. This is verifiable by anyone. PostgreSQL's `digest()` function makes this efficient:

```sql
CREATE TABLE journal_entries (
    id              BIGSERIAL PRIMARY KEY,
    previous_hash   BYTEA NOT NULL,
    hash            BYTEA NOT NULL UNIQUE,
    entry_data      JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Use trigger to auto-compute hash on insert
CREATE OR REPLACE FUNCTION compute_journal_hash()
RETURNS TRIGGER AS $$
BEGIN
    NEW.hash := digest(
        NEW.entry_data::text || NEW.previous_hash::text,
        'sha256'
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

---

## 7. Common Accounting Mistakes Engineers Make

| Mistake | Why It's Wrong | Correct Approach |
|---------|---------------|------------------|
| "I'll just add/ subtract from balance" | No audit trail, can't detect bugs | Use double-entry journal |
| "I'll store debit/credit as signed numbers" | Confuses sign conventions | Store DR/CR separately (UNSIGNED) |
| "I can DELETE a wrong entry" | Destroys audit trail. Regulators flag this. | Use reversing entries |
| "Balance = last row in the table" | Race conditions, incorrect aggregation | Compute sum of ALL entries (idempotent) |
| "Fees are just a subtraction" | Fees are revenue — they affect Equity | Record fee as separate credit to Revenue |
| "Settlement happens instantly" | Banks batch settle T+1 | Design for settlement lag |

---

## 8. Practical: Reading Journal Entry Code

Here's a snippet of what the actual Go code will look like in our platform (from Phase 08):

```go
type JournalEntry struct {
    ID            int64           `json:"id" db:"id"`
    TransactionID string          `json:"transaction_id" db:"transaction_id"`
    Entries       []JournalLine   `json:"entries"`     // DR/CR pair(s)
    PreviousHash  []byte          `json:"previous_hash" db:"previous_hash"`
    Hash          []byte          `json:"hash" db:"hash"`
    CreatedAt     time.Time       `json:"created_at" db:"created_at"`
}

type JournalLine struct {
    AccountID string `json:"account_id" db:"account_id"`
    Debit     int64  `json:"debit,omitempty" db:"debit"`   // Always unsigned
    Credit    int64  `json:"credit,omitempty" db:"credit"` // Always unsigned
}
```

The `create_journal_entry` stored procedure (PostgreSQL, SECURITY DEFINER):

```sql
-- Wrapped in a SECURITY DEFINER procedure for audit traceability
CREATE OR REPLACE PROCEDURE create_journal_entry(
    p_transaction_id TEXT,
    p_entries        JSONB,  -- Array of {account_id, debit, credit}
    INOUT p_entry_id BIGINT
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_total_debit  BIGINT := 0;
    v_total_credit BIGINT := 0;
    v_prev_hash    BYTEA;
BEGIN
    -- Validate total DR = total CR
    SELECT SUM(COALESCE((e->>'debit')::BIGINT, 0)),
           SUM(COALESCE((e->>'credit')::BIGINT, 0))
    INTO v_total_debit, v_total_credit
    FROM jsonb_array_elements(p_entries) AS e;

    IF v_total_debit != v_total_credit THEN
        RAISE EXCEPTION 'Unbalanced journal entry: DR=% CR=%', v_total_debit, v_total_credit;
    END IF;

    -- Get previous hash
    SELECT hash INTO v_prev_hash FROM journal_entries
    ORDER BY id DESC LIMIT 1;

    IF v_prev_hash IS NULL THEN
        v_prev_hash := decode('00000000000000000000000000000000', 'hex');
    END IF;

    -- Insert entry
    INSERT INTO journal_entries (transaction_id, entries, previous_hash, hash)
    VALUES (p_transaction_id, p_entries, v_prev_hash, '\\x')
    RETURNING id INTO p_entry_id;
END;
$$;
```

---

## Check Questions

1. What's the accounting equation? What happens when a user tops up their wallet?
2. Does a debit increase or decrease a liability account?
3. If a system stores wallet balances instead of computing them from ledgers, why is that dangerous?
4. What's the difference between PENDING and POSTED in accounting terms?
5. How do you correct a journal entry that was posted with the wrong amount?
6. Why is hash chaining used in the ledger?
7. If a user transfers 100K VND to another user and the platform fee is 2K, what does the journal entry look like?
8. What does "trial balance" check for?

---

## Next Module

[Module 03 — Payment Security & Compliance](03-payment-security-and-compliance.md)

> Understanding accounting is the only way to build a payment platform that doesn't lose money — or go to jail.
