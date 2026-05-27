# Module 06 — Settlement & Reconciliation

## Duration: 3–4 hours | Critical: Yes

---

## Learning Objectives

By the end of this module, you will understand:
- What settlement is and why it happens separately from the payment
- The settlement lifecycle: capture → clearing → netting → funding
- How bank settlement works: NOSTRO/VOSTRO, SWIFT, ACH
- Reconciliation: matching internal records against external (bank) records
- Break handling: what to do when records don't match
- End-of-day (EOD) settlement batch design

---

## 1. Settlement Fundamentals

### What Is Settlement?

**Settlement** is the actual movement of money between financial institutions. It happens separately from the payment authorization.

```
PAYMENT TIMELINE:
Transaction Time:       T+0 (seconds)
  ┌─ Authorization: Funds reserved ✓
  └─ Capture: Funds committed ✓

SETTLEMENT TIMELINE:    T+1 (next business day)
  ┌─ Clearing: Calculate net positions between banks
  └─ Settlement: Actual funds move via central bank
```

**Critical insight**: When a user pays on MoMo, the wallet balance changes immediately. But MoMo's bank account doesn't see that money for 1-3 days.

### Settlement Models

| Model | Description | Latency | Who Uses |
|-------|-------------|---------|----------|
| **Real-time gross settlement (RTGS)** | Each payment settled individually via central bank | Seconds | High-value transfers, interbank |
| **Deferred net settlement (DNS)** | Net positions calculated at EOD, settled once | T+1 | Card networks, ACH |
| **Batch settlement** | Files exchanged, processed in batch | T+1 to T+3 | Traditional banking |
| **Wallet-internal** | Settlement happens within the platform | Instant | MoMo wallet-to-wallet |

### For Our Platform

| Payment Type | Settlement Mechanism | Latency |
|-------------|---------------------|---------|
| Wallet → Wallet (same platform) | Internal ledger update | Instant |
| Wallet → Bank (withdrawal) | Batch bank file → ACH/IBPS | T+1 |
| Bank → Wallet (top-up) | Bank webhook → internal credit | Near-real (minutes) |
| QR → Merchant (interbank) | NAPAS clearing → bank settlement | T+1 |
| Card payment | Payment network clearing | T+1 to T+2 |

---

## 2. The Settlement Lifecycle

### Step 1: Capture and Accumulation

Throughout the day, every transaction is captured and stored:

```
08:00 — Txn #1: User A → Merchant X    50,000 VND
09:15 — Txn #2: User B → Merchant X    120,000 VND
10:30 — Txn #3: User A → Merchant Y    30,000 VND
...
23:59 — Last transaction of the day
```

### Step 2: End-of-Day Cutoff

At a defined time (typically 23:59 VNT or 23:59 UTC), the system runs the "EOD batch":

1. **Lock all transaction processing** (or accept but defer to next day)
2. **Generate settlement report** for each bank/partner
3. **Calculate net positions**
4. **Submit settlement file** to bank/NAPAS

### Step 3: Clearing Calculation

```
Daily Transaction Summary:
Bank A issued $100,000 in payments to Bank B's merchants
Bank B issued $75,000 in payments to Bank A's merchants

Net position:
Bank A owes Bank B: $25,000  ($100K - $75K = $25K net outflow)
Bank B receives:    $25,000  (net inflow)
```

### Step 4: Funding (Actual Money Movement)

```
Bank A's settlement account at SBV:    -$25,000
Bank B's settlement account at SBV:    +$25,000
```

In the real world, this happens through:
- **Vietnam**: IBPS (Interbank Payment System) operated by SBV
- **Global**: SWIFT, Fedwire, SEPA

---

## 3. NOSTRO/VOSTRO Accounts

### The Concept

When two banks do business across borders (or even domestically), they hold accounts with each other:

| Term | Bank A's Perspective | Bank B's Perspective |
|------|---------------------|---------------------|
| **NOSTRO** ("our money at your bank") | Bank A's account at Bank B | Bank B's account at Bank A |
| **VOSTRO** ("your money at our bank") | Bank B's account at Bank A | Bank A's account at Bank B |

### In Our Platform

Our platform's bank account at Vietcombank is:
- **Our perspective**: "Our Vietcombank account" (asset)
- **Vietcombank's perspective**: Our VOSTRO account (liability to them)

**Settlement** = reconciling our internal ledger with the actual balance in our NOSTRO account at the bank.

---

## 4. Settlement Files

### Structure of a Settlement File

```
SETTLEMENT_FILE_2026-03-21
═══════════════════════════════
HEADER|API_PLATFORM|VE1|20260321|20260322
DETAIL|TXN001|VIETCOMBANK|A|50000|20260321|REF001
DETAIL|TXN002|SACOMBANK|B|120000|20260321|REF002
DETAIL|TXN003|VIETCOMBANK|A|30000|20260321|REF003
TRAILER|3|200000|0|200000
═══════════════════════════════
```

| Record Type | Fields |
|-------------|--------|
| **HEADER** | File type, Platform ID, Bank code, File date, Settlement date |
| **DETAIL** | Txn ID, Bank code, Debit/Credit flag, Amount, Date, Reference |
| **TRAILER** | Count, Total amount, Fee total, Net settlement |

### NACHA Format (ACH — US)

For US ACH settlement (if expanding):

```
101 031000010 123456789 20260321   0940A094101BANK OF AMERICA*COMPANY NAME
627 12345678912345678 0000500000  50000000  User A                   Merchant X
627 98765432198765432 0000120000  12000000  User B                   Merchant Y
822 12345678912345678           2   62000000  0
```

### NAPAS Files (Vietnam)

NAPAS uses ISO 8583 or XML-based file formats for settlement. Key fields:
- Transaction ID
- Card PAN (masked/encrypted)
- Amount
- Fee (split: issuer fee, acquirer fee, network fee)
- Settlement date
- Response code

---

## 5. Reconciliation

### What Is Reconciliation?

**Reconciliation** is the process of matching our internal records against the bank's settlement report to ensure every transaction is accounted for.

### The Four-Way Match

```
Internal Ledger ──┐
                  ├── Match 1: Internal completeness
                  │     All our transactions accounted for?
                  │
Bank Settlement ──┤
Report            ├── Match 2: Internal vs. Bank
                  │     Do our records + bank records match?
                  │
Bank Statement ───┤
(Actual funds)     ├── Match 3: Settlement vs. Actual Funds
                       Did the bank move the promised amount?
```

### Reconciliation Process

```go
type ReconciliationEngine struct {
    internalRepo LedgerRepository
    bankRepo     SettlementFileRepository
    statementRepo BankStatementRepository
}

func (e *ReconciliationEngine) Reconcile(ctx context.Context, date string) (*ReconciliationReport, error) {
    // Step 1: Get internal transactions for the date
    internalTxns, _ := e.internalRepo.GetByDate(ctx, date)

    // Step 2: Parse bank settlement file
    bankTxns, _ := e.bankRepo.ParseSettlementFile(ctx, date, "VIETCOMBANK")

    // Step 3: Match
    var matched, unmatchedInternal, unmatchedBank []Transaction
    internalMap := make(map[string]Transaction)

    for _, txn := range internalTxns {
        internalMap[txn.ReferenceID] = txn
    }

    for _, bankTxn := range bankTxns {
        if txn, ok := internalMap[bankTxn.ReferenceID]; ok {
            if txn.Amount == bankTxn.Amount {
                matched = append(matched, txn)
                delete(internalMap, bankTxn.ReferenceID)
            } else {
                unmatchedInternal = append(unmatchedInternal, txn)
            }
        } else {
            unmatchedBank = append(unmatchedBank, bankTxn)
        }
    }

    // Step 3: Verify actual funds against settlement
    balanceCheck := e.verifyFundMovement(ctx, date, bankTxns)

    // Step 4: Generate report
    return &ReconciliationReport{
        Date:              date,
        Matched:           len(matched),
        UnmatchedInternal: len(unmatchedInternal),
        UnmatchedBank:     len(unmatchedBank),
        BalanceCheckOk:    balanceCheck,
       DiscrepancyAmount:  calculateDiscrepancy(matched, bankTxns),
    }, nil
}
```

### Discrepancy Types and Handling

| Discrepancy | Likely Cause | Action |
|-------------|-------------|--------|
| Internal txn missing from bank file | Bank delayed/batched differently | Check next day's file |
| Bank txn missing from internal | Our system didn't capture | Investigate integration |
| Amount differs | Fee deduction, currency conversion | Add fee adjustment logic |
| Duplicate in bank file | Bank error | Flag for manual resolution |
| Duplicate in internal | Bug in our system | Reversal + fix |

### Settlement vs. Statement Reconciliation

```
Bank Settlement File shows:          5,000,000 VND settled
Bank Statement (actual) shows:       4,970,000 VND deposited

Discrepancy: 30,000 VND
Causes:
  - Bank fees (not in settlement file)   10,000 VND
  - Exchange rate difference              5,000 VND
  - Unidentified adjustment              15,000 VND ← NEEDS INVESTIGATION
```

---

## 6. EOD Settlement Batch Design

### Batch Flow

```
┌─────────────────────────────┐
│ 1. CUTOFF                    │ 23:59:00
│    Stop accepting new txns   │
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 2. FREEZE LEDGER            │ 23:59:05
│    Snapshot all unsettled   │
│    transactions             │
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 3. GENERATE SETTLEMENT FILE │ 00:00:00
│    Group by bank/merchant   │
│    Calculate net positions  │
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 4. SUBMIT TO BANK           │ 00:05:00
│    Upload via SFTP/API      │
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 5. WAIT FOR CONFIRMATION    │ T+1 (hours)
│    Bank processes + returns │
│    settlement report        │
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 6. RECONCILE                │ T+1
│    Match internal vs. bank  │
│    Handle breaks            │
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 7. UPDATE LEDGER            │ T+1
│    Mark transactions as     │
│    "settled"                │
└─────────────────────────────┘
```

### Error Handling

| Error | Impact | Handling |
|-------|--------|----------|
| Bank file upload fails | Settlement delayed | Retry (5x), then on-call |
| Bank rejects file | Format error | Pause settlement, manual fix |
| Bank processes partial file | Partial settlement | Reconcile only processed txns |
| Cutoff missed txn | Txn in wrong batch | Roll to next day |
| Settlement amount wrong | Money loss | Stop, investigate, fix |

---

## 7. Practical: Settlement Event in Our Platform

```go
type SettlementBatch struct {
    ID           uuid.UUID          `json:"id"`
    BatchDate    string             `json:"batch_date"`    // 2026-03-21
    Status       SettlementStatus   `json:"status"`
    Entries      []SettlementEntry  `json:"entries"`
    TotalAmount  int64              `json:"total_amount"`
    NetAmount    int64              `json:"net_amount"`
    CreatedAt    time.Time          `json:"created_at"`
    SettledAt    *time.Time         `json:"settled_at"`
}

type SettlementEntry struct {
    ReferenceID      string    `json:"reference_id"`       // Our txn ID
    BankCode         string    `json:"bank_code"`          // e.g., VCB
    Amount           int64     `json:"amount"`
    Fee              int64     `json:"fee"`
    SettlementAmount int64     `json:"settlement_amount"`  // Amount - Fee
    Direction        string    `json:"direction"`          // DEBIT or CREDIT
}

type SettlementStatus string

const (
    StatusPending     SettlementStatus = "PENDING"
    StatusSubmitted   SettlementStatus = "SUBMITTED"
    StatusConfirmed   SettlementStatus = "CONFIRMED"
    StatusReconciled  SettlementStatus = "RECONCILED"
    StatusFailed      SettlementStatus = "FAILED"
)
```

---

## 8. Common Settlement Architecture Mistakes

| Mistake | Consequence |
|---------|-------------|
| Assuming banks settle instantly | Balance always appears wrong until T+1 |
| No reconciliation step | Unmatched transactions pile up, impossible to fix |
| Manual settlement process | Human error, missed deadlines, regulatory fines |
| Not accounting for bank fees | Reconciliation always shows discrepancy |
| Single settlement file for all partners | Each bank has different format requirements |
| No retry logic | One failed upload blocks ALL settlement |
| Not logging the raw bank file | Cannot audit what the bank said vs. what we processed |

---

## Check Questions

1. What's the difference between capture and settlement?
2. Why does settlement happen on T+1 while wallet balance updates instantly?
3. What's the purpose of the clearing step?
4. What does NOSTRO mean? VOSTRO?
5. In the reconciliation process, what does an "unmatched bank transaction" mean?
6. What should happen if the EOD settlement file fails to upload to the bank?
7. Why is it important to freeze the ledger before generating a settlement file?
8. If a bank processes partial settlement, how do you handle the remaining transactions?

---

## Next Module

[Module 07 — Go Language & Ecosystem for Payment Systems](07-go-language-and-ecosystem.md)

> Settlement is where the platform meets the real banking world. It's the least glamorous part of the system — and the most financially critical.
