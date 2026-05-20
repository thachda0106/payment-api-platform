# Module 01 — Payment Domain Fundamentals

## Duration: 2–3 hours | Critical: Yes

---

## Learning Objectives

By the end of this module, you will understand:
- The 4-party payment model and all actors involved
- The full payment lifecycle: authorization → capture → settlement
- Difference between payment gateway, payment processor, and acquirer
- How digital wallets (like MoMo) fit into the traditional payment model
- Payment methods: cards, bank transfers, wallets, QR codes
- The concept of payment rails and clearing

---

## 1. The 4-Party Payment Model

Every card payment involves exactly 4 parties. Understanding this model is the foundation of payment engineering.

```
┌──────────┐          ┌──────────┐
│ CUSTOMER │────────▶▶│ MERCHANT │
│ (Payer)  │          │ (Payee)  │
└──────────┘          └──────────┘
     │                      │
     │ uses card/wallet     │ contracts with
     ▼                      ▼
┌──────────┐          ┌──────────┐
│  ISSUER  │◀─────────│ ACQUIRER │
│ (Issuing │  payment │(Merchant │
│  Bank)   │  network │  Bank)   │
└──────────┘          └──────────┘
         \              /
          ─────────────
          Payment Network
          (Visa, Mastercard,
           NAPAS, UnionPay)
```

### The 4 Parties

| Party | Role | Real-World Example (Vietnam) |
|-------|------|-----------------------------|
| **Cardholder** | The person paying | You, buying coffee |
| **Merchant** | The business receiving payment | Highlands Coffee |
| **Issuer (Issuing Bank)** | Bank that issued the customer's card/wallet | Vietcombank, Momo |
| **Acquirer (Acquiring Bank)** | Bank that processes payments for the merchant | Sacombank, VNPAY |

### The 5th Player: Payment Network

The payment network (also called "card scheme" or "switch") provides the infrastructure that connects issuers and acquirers. Examples:
- **Global**: Visa, Mastercard
- **Vietnam**: NAPAS (National Payment Corporation of Vietnam)
- **Internal wallet**: MoMo's own internal switch for wallet-to-wallet transfers

### How MoMo Fits

MoMo plays **three roles simultaneously**:
1. **Issuer**: Issues MoMo wallet accounts to users
2. **Acquirer**: Acquires merchants who accept MoMo QR payments
3. **Payment Network**: Routes transactions between MoMo users internally (no NAPAS needed for wallet-to-wallet)

---

## 2. The Payment Lifecycle

A payment is not a single operation. It's a multi-step lifecycle:

```
┌─────────────┐    ┌──────────┐    ┌───────────┐    ┌───────────┐
│ AUTHORIZATION│───▶│ CAPTURE  │───▶│ CLEARING  │───▶│ SETTLEMENT│
│  (Auth)     │    │ (Capture)│    │ (Clearing)│    │(Settlement)│
│ "Can they   │    │ "Take the│    │ "Calculate│    │ "Move the │
│  pay?"      │    │  money"  │    │  net owed"│    │  money"   │
└─────────────┘    └──────────┘    └───────────┘    └───────────┘
     seconds           seconds          hours           T+1 days
```

### Step 1: Authorization

**What happens**: The system checks if the customer has sufficient funds/credit and if the transaction passes fraud checks.

```
Customer → Merchant → Acquirer → Payment Network → Issuer → Auth Response
                                                              │
                                          ┌───────────────────┘
                                          ▼
                                    APPROVED (with auth code)
                                    or DECLINED (with reason code)
```

Key concepts:
- **Auth hold**: The amount is reserved (held) but NOT yet moved. Think of it as a "promise to pay."
- **Auth code**: A unique identifier for this authorization (6 chars typically)
- **Auth expiry**: Holds expire after 7-30 days if not captured
- **Reversal**: An auth can be reversed (released) if the merchant cancels

### Step 2: Capture

**What happens**: The merchant "captures" the authorized amount, telling the bank to actually move the money.

```
Authorize $100  →  Capture $100  (full capture)
Authorize $100  →  Capture $85   (partial capture — e.g., item out of stock)
Authorize $100  →  Capture $100  →  Capture $0 (void/auth reversal)
```

Key concepts:
- **Delayed capture**: E-commerce authorizes at checkout, captures at shipment
- **Auto-capture**: Physical POS captures immediately after auth
- **Capture window**: Time limit to capture after auth (typically 7 days)
- **Partial capture**: Capture less than authorized (remaining auth expires)

### Step 3: Clearing

**What happens**: At end of day, all captured transactions are batched and sent through the payment network. The network calculates net settlement amounts per bank.

This is a **batch process** — transactions are not settled one by one. A settlement file is generated containing all transactions for that day.

### Step 4: Settlement

**What happens**: Actual funds move between banks through the central bank or settlement institution.

```
Net settlement calculation:
Bank A owes Bank B: $10,000
Bank B owes Bank A: $7,500
Bank A pays Bank B: $2,500
```

In Vietnam, NAPAS handles clearing and settlement for card transactions. For wallet transactions (MoMo), settlement is internal.

---

## 3. Payment Methods Deep-Dive

### Card Payments

```
┌─────────────────────────────────────────────┐
│ Card-based payment flow (Customer Present)    │
├─────────────────────────────────────────────┤
│ 1. Customer taps/swipes/inserts card         │
│ 2. Terminal reads card data (EMV chip/NFC)   │
│ 3. Terminal sends auth request to acquirer   │
│ 4. Acquirer routes to payment network        │
│ 5. Network routes to issuer                  │
│ 6. Issuer checks: funds? fraud? pin?         │
│ 7. Response flows back: approved/declined     │
│ 8. Terminal prints receipt (if approved)     │
└─────────────────────────────────────────────┘
```

### Digital Wallet (MoMo-like)

```
┌─────────────────────────────────────────────┐
│ Wallet Payment Flow (P2P Transfer)            │
├─────────────────────────────────────────────┤
│ 1. User A opens wallet app                   │
│ 2. Selects "Send Money" to User B            │
│ 3. Enters amount + PIN/OTP                   │
│ 4. Wallet backend:                           │
│    a. Validate PIN/biometric                 │
│    b. Check balance (User A)                 │
│    c. Fraud check                            │
│    d. Debit User A's wallet balance          │
│    e. Credit User B's wallet balance         │
│    f. Record transaction in ledger           │
│    g. Send push notification to both         │
│ 5. Both balances update in real-time         │
└─────────────────────────────────────────────┘
```

### QR Code Payments (VietQR / MoMo QR)

Vietnam has standardized on VietQR (built on EMVCo QR standard):

```
Customer scans merchant's QR:
┌──────────┐    scan QR    ┌──────────────┐
│ Customer │──────────────▶│ Merchant QR   │
│  Phone   │               │ (static/dynamic)│
└──────────┘               └──────────────┘
     │                            │
     │ read merchant info:        │ QR contains:
     │ - merchant ID              │ - merchant ID
     │ - amount (if dynamic)      │ - bank info
     │ - bill reference           │ - amount (dynamic QR)
     ▼                            │
┌──────────────────────────────┐  │
│ Customer's wallet/bank app   │  │
│ confirm→auth→pay             │  │
└──────────────────────────────┘  │
     │                            │
     │ transfer via NAPAS or      │
     │ internal wallet network    │
     ▼                            ▼
┌──────────────────────────────┐
│ Merchant receives notification│
│ and funds credited to account │
└──────────────────────────────┘
```

Two types of QR:
| Type | Description | Use Case |
|------|-------------|----------|
| **Static QR** | Fixed merchant ID, no amount. User enters amount. | Small shops, printed QR code |
| **Dynamic QR** | Generated per transaction. Includes amount + reference. | E-commerce, invoices |

### Bank Transfer (Direct Debit / Top-Up)

When a user tops up their wallet from a bank account:

```
┌──────────┐   initiate top-up   ┌──────────┐
│ MoMo App │────────────────────▶│ MoMo     │
│ (User)   │                     │ Backend  │
└──────────┘                     └──────────┘
                                      │
                            create funding request
                                      │
                                      ▼
                                 ┌──────────┐
                                 │ Payment  │
                                 │ Gateway  │
                                 │ (VNPAY,  │
                                 │ NAPAS)   │
                                 └──────────┘
                                      │
                            redirect to bank
                                      │
                                      ▼
                                 ┌──────────┐
                                 │ User's   │
                                 │ Bank App │
                                 │ or IB    │
                                 └──────────┘
                                      │
                            user confirms + OTP
                                      │
                                      ▼
                                 ┌──────────┐
                                 │ Bank     │
                                 │ debits   │
                                 │ account  │
                                 └──────────┘
                                      │
                            callback/webhook
                                      │
                                      ▼
                                 ┌──────────┐
                                 │ MoMo     │
                                 │ credits  │
                                 │ wallet   │
                                 └──────────┘
```

---

## 4. Payment Ecosystem in Vietnam

Understanding the local ecosystem is critical since this platform targets Vietnam:

### Key Players

| Entity | Role | Relevance to Our Platform |
|--------|------|--------------------------|
| **NAPAS** | National payment switch. Routes interbank card + QR transactions. | Must integrate for interbank transfers |
| **SBV (State Bank of Vietnam)** | Central bank. Regulates all financial services. Holds settlement accounts. | Compliance & licensing |
| **VietQR** | National QR standard (EMVCo-based) | QR generation/parsing |
| **VNPAY** | Payment gateway (largest in Vietnam) | Integration for card acceptance |
| **Vietcombank, BIDV, VietinBank** | Major issuing banks | Bank transfer integration |

### Regulatory Framework

| Regulation | Focus |
|------------|-------|
| **Circular 39/2014/TT-NHNN** | Intermediary payment services |
| **Circular 23/2019/TT-NHNN** | E-wallet operations, KYC requirements |
| **Decree 101/2012** | Non-cash payments |
| **Circular 47/2024/TT-NHNN** | Updated e-wallet KYC and transaction limits |

### Wallet KYC Tiers (Vietnam Regulation)

| Tier | Requirements | Monthly Limit |
|------|-------------|---------------|
| Tier 1 | Phone number only | 10M VND |
| Tier 2 | Phone + ID + photo + bank account link | 100M VND |
| Tier 3 | Phone + ID + biometric + bank link | Full (varies by provider) |

---

## 5. Transaction Types Reference

| Type | Description | Example |
|------|-------------|---------|
| **P2P Transfer** | Person-to-person wallet transfer | Send money to friend |
| **P2M Payment** | Person-to-merchant | Pay at coffee shop via QR |
| **Top-Up** | Add funds from bank account to wallet | Nạp tiền từ ngân hàng |
| **Withdrawal** | Move funds from wallet to bank account | Rút tiền về tài khoản |
| **Bill Payment** | Pay utility bills | Điện, nước, Internet |
| **Refund** | Merchant-initiated return of funds | Product return |
| **Chargeback** | Bank/Cardholder-initiated forced refund | Disputed transaction |
| **Cash-In** | Agent deposit (over-the-counter) | Deposit cash at convenience store |
| **Cash-Out** | Agent withdrawal | Withdraw cash at agent location |

---

## 6. Payment Message Formats

### ISO 8583 (Financial Transaction Card Originated Messages)

The standard message format used by card networks. Key fields:

```
ISO 8583 Message Structure:
┌──────────┬───────────────┬──────────────┬──────────────┐
│ MTI (4)  │ Bitmap (16/32)│ Data Fields  │              │
│ Message  │ Which fields  │ (variable)   │              │
│ Type     │ are present   │              │              │
└──────────┴───────────────┴──────────────┴──────────────┘

Common MTIs:
0100 — Authorization Request    0110 — Authorization Response
0200 — Financial Request        0210 — Financial Response
0400 — Reversal Request         0410 — Reversal Response
```

### ISO 20022 (Newer, XML/JSON-based)

Used for SEPA, SWIFT, and increasingly in Vietnam. More flexible than ISO 8583. Our platform should support both.

---

## 7. Key Design Decisions for Our Platform

Based on the Product Discovery document, here are the core decisions you need to understand before building:

| Decision | Rationale |
|----------|-----------|
| **Database-per-service** | Financial data isolation required by regulation |
| **Synchronous wallet updates** | Users expect real-time balance reflection |
| **Async settlement** | Settlement is inherently a batch process |
| **Idempotency on all mutations** | Double-charge prevention is non-negotiable |
| **Outbox pattern for events** | Guarantee event delivery for audit trail |
| **Double-entry ledger** | Financial correctness requires immutable double-entry |

---

## Check Questions

Answer these before moving to Module 02:

1. What are the 4 parties in a card payment?
2. What's the difference between authorization and capture?
3. How does MoMo act as issuer, acquirer, AND network simultaneously?
4. What's the difference between a static and dynamic QR code?
5. What does "T+1 settlement" mean?
6. Why is ISO 8583 still relevant when ISO 20022 exists?
7. What are the 3 KYC tiers for e-wallets in Vietnam?

---

## Next Module

[Module 02 — Financial Accounting for Engineers](02-financial-accounting-for-engineers.md)

> Accounting is the language of finance. If a payment system's ledger is wrong, the business is dead.
