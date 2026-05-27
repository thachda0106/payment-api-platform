# Module 05 — Fraud Detection Fundamentals

## Duration: 2–3 hours | Critical: Recommended

---

## Learning Objectives

By the end of this module, you will understand:
- The fraud landscape: what types of fraud target payment platforms
- Rule-based fraud detection: velocity checks, thresholds, blacklists
- Machine learning basics for fraud scoring
- Real-time vs. batch fraud detection
- How to build a fraud detection pipeline without compromising payment latency
- False positives vs. false negatives: the trade-off

---

## 1. Types of Payment Fraud

### Account Takeover (ATO)

```
Attack flow:
1. Attacker obtains credentials (phishing, data breach, password reuse)
2. Attacker logs in as legitimate user
3. Attacker transfers funds to mule accounts
4. Attacker withdraws cash or cashes out via crypto

Detection signals:
- Login from unusual location/device
- Rapid password changes
- New device fingerprint
- Unusual transaction patterns (amount, frequency, recipient)
```

### Synthetic Identity Fraud

```
1. Attacker creates a fake identity (real ID + fake selfie, or fake everything)
2. Passes KYC with forged/manipulated documents
3. Builds transaction history (small legitimate transactions)
4. Exploits credit/fast withdrawal → disappears with funds

Detection signals:
- Identity elements don't correlate (e.g., phone registered 5 min ago, ID from different province)
- No social footprint
- Cross-bureau checks fail
- Device fingerprint matches other suspicious accounts
```

### Card Testing / BIN Attacks

```
1. Attacker obtains a list of PANs (card numbers)
2. Uses automated scripts to test each PAN with $1 or $0 auth requests
3. Validates which cards are active
4. Uses active cards for high-value fraud

Detection signals:
- Rapid sequential authorization attempts
- Many different cards from same IP/device
- Attempt amounts identical (e.g., all $1.00)
- High decline rate followed by success
```

### Merchant Fraud

| Type | Description |
|------|-------------|
| **Friendly fraud** | Customer disputes legitimate charge (chargeback abuse) |
| **Collusion** | Merchant and buyer collude (buy with stolen card, split proceeds) |
| **Refund fraud** | Merchant processes refund to different card |
| **Drop-shipping fraud** | Merchant accepts payment, never ships goods |

### Transaction Reversal Abuse

```
1. Attacker sends money to accomplice
2. Attacker reports unauthorized transaction
3. Platform reverses the transaction
4. Accomplice's account keeps the original amount (if withdrawn before reversal)

This is why holds and settlement delays exist.
```

---

## 2. Fraud Detection Architecture

### Real-Time vs. Batch

| Type | Latency | Use Case | Example |
|------|---------|----------|---------|
| **Pre-transaction** | < 100ms | Block fraud before money moves | Auth check, velocity check |
| **Post-transaction** | Minutes | Review suspicious transactions | Manual review queue |
| **Batch (daily)** | Hours | Detect patterns, update models | AML screening, SAR filing |

### Our Architecture (Synchronous + Async)

```
┌──────────┐  Auth Request  ┌──────────────┐  Score + Decision  ┌────────────┐
│  Client   │───────────────▶│  API Gateway   │◀────────────────│ Fraud      │
│           │                │  (Kong)        │───── risk_score──▶│ Service    │
└──────────┘                └───────┬────────┘                  └────────────┘
                                    │                                    ▲
                                    ▼                                    │
                              ┌──────────────┐                    ┌────────────┐
                              │  Payment      │  send event        │ Rule Engine│
                              │  Service      │────────────────────▶│ (async)    │
                              │               │                    └────────────┘
                              └──────────────┘
```

### Key Components

| Component | Purpose | Latency Budget |
|-----------|---------|:-------------:|
| **Rule Engine** | Evaluate deterministic rules (velocity, threshold, blacklist) | < 10ms |
| **ML Scorer** | Score transaction with ML model for anomaly detection | < 50ms |
| **Device Fingerprinting** | Identify device across sessions | < 20ms |
| **IP/BIN Reputation** | Check IP, BIN, email against known bad actors | < 30ms |
| **Manual Review Queue** | Flagged transactions awaiting human review | Minutes |
| **Case Management** | Investigate fraud cases, file SARs | Days |

---

## 3. Rule Engine Design

### Core Rules

```go
type FraudRule interface {
    Name() string
    Evaluate(ctx context.Context, txn Transaction) (*FraudDecision, error)
    Priority() int // Lower number = higher priority
}

type FraudDecision struct {
    Action      FraudAction // ALLOW, FLAG, BLOCK, CHALLENGE
    Score       int         // 0-100 (0 = safe, 100 = definitely fraud)
    Reason      string
    RuleName    string
}

type FraudAction int

const (
    ActionAllow     FraudAction = iota // Pass — no action needed
    ActionFlag                         // Flag for manual review
    ActionBlock                        // Block the transaction
    ActionChallenge                    // Step-up auth (OTP, biometric)
)
```

### Rule Implementations

```go
// Velocity Rule — same recipient, too many in short window
type VelocityRule struct {
    maxTransactions int
    windowDuration  time.Duration
    cache           redis.Client
}

func (r *VelocityRule) Evaluate(ctx context.Context, txn Transaction) (*FraudDecision, error) {
    key := fmt.Sprintf("velocity:%s:%s", txn.SenderID, txn.ReceiverID)
    count, _ := r.cache.Incr(ctx, key).Result()
    if count == 1 {
        r.cache.Expire(ctx, key, r.windowDuration)
    }

    if count > r.maxTransactions {
        return &FraudDecision{
            Action: ActionFlag,
            Score:  60,
            Reason: fmt.Sprintf("Velocity check: %d txns to same recipient in %v", count, r.windowDuration),
        }, nil
    }
    return &FraudDecision{Action: ActionAllow}, nil
}

// Amount Threshold Rule
type AmountThresholdRule struct {
    singleMax   int64
    dailyMax    int64
}

func (r *AmountThresholdRule) Evaluate(ctx context.Context, txn Transaction) (*FraudDecision, error) {
    if txn.Amount > r.singleMax {
        return &FraudDecision{
            Action: ActionFlag,
            Score:  50,
            Reason: fmt.Sprintf("Amount %d exceeds single limit %d", txn.Amount, r.singleMax),
        }, nil
    }
    return &FraudDecision{Action: ActionAllow}, nil
}

// Device Anomaly Rule
type DeviceAnomalyRule struct {
    deviceRepo DeviceRepository
}

func (r *DeviceAnomalyRule) Evaluate(ctx context.Context, txn Transaction) (*FraudDecision, error) {
    knownDevice, _ := r.deviceRepo.GetUserDevice(ctx, txn.SenderID)
    if knownDevice != nil && knownDevice.Fingerprint != txn.DeviceFingerprint {
        return &FraudDecision{
            Action: ActionChallenge,
            Score:  70,
            Reason: "Unknown device for user",
        }, nil
    }
    return &FraudDecision{Action: ActionAllow}, nil
}
```

### Rule Engine Orchestration

```go
type RuleEngine struct {
    rules []FraudRule
}

func (e *RuleEngine) Evaluate(ctx context.Context, txn Transaction) *FraudDecision {
    // Sort by priority
    sort.Slice(e.rules, func(i, j int) bool {
        return e.rules[i].Priority() < e.rules[j].Priority()
    })

    for _, rule := range e.rules {
        decision, err := rule.Evaluate(ctx, txn)
        if err != nil {
            log.Printf("Rule %s failed: %v", rule.Name(), err)
            continue
        }

        switch decision.Action {
        case ActionBlock:
            return decision // Short-circuit: block immediately
        case ActionChallenge:
            return decision
        case ActionFlag:
            // Continue evaluating, but remember this flag
        }
    }

    // Aggregate score from all rules
    return &FraudDecision{Action: ActionAllow, Score: 0}
}
```

---

## 4. ML-Based Fraud Detection

### Feature Engineering

The ML model doesn't get raw data — it gets **features** computed from raw data:

| Feature Category | Examples |
|-----------------|----------|
| **Transaction features** | Amount, hour of day, day of week, distance between sender/receiver locations |
| **User features** | Account age, number of past transactions, KYC tier, past chargeback ratio |
| **Device features** | Device age, number of accounts using this device, rooted/jailbroken? |
| **Network features** | IP reputation (known proxy/VPN?), ASN, geo-velocity (country changes) |
| **Behavioral features** | Average transaction size, typical session duration, typical time of day |

### Model Types

| Model | Pros | Cons | Use Case |
|-------|------|------|----------|
| **Logistic Regression** | Interpretable, fast | Limited expressiveness | Baseline scorer |
| **Random Forest** | Handles non-linear patterns, interpretable | Can overfit | Production scorer |
| **XGBoost/LightGBM** | State-of-the-art for tabular data | Less interpretable | High-performance scorer |
| **Neural Networks** | Captures complex interactions | Black box, needs more data | Behavioral embedding |
| **Graph Neural Network** | Captures relationship between entities | Complex to deploy | Link analysis (collusion) |

### Model Serving

```go
// Feature vector computed at transaction time
type FraudFeatures struct {
    Amount            float64 `json:"amount"`
    HourOfDay         int     `json:"hour_of_day"`
    IsWeekend         bool    `json:"is_weekend"`
    AccountAgeDays    int     `json:"account_age_days"`
    TxnCountLast24h   int     `json:"txn_count_last_24h"`
    AvgAmount7d       float64 `json:"avg_amount_7d"`
    DeviceCount3d     int     `json:"device_count_3d"`
    IPReputationScore float64 `json:"ip_reputation_score"`
    IsProxy           bool    `json:"is_proxy"`
    // ... ~100 more features
}

// Model inference (Go calling a compiled ONNX model or Python sidecar)
func (s *MLScorer) Score(ctx context.Context, features *FraudFeatures) (float64, error) {
    // Option 1: Native ONNX inference in Go (using onnxruntime-go)
    // Option 2: gRPC call to Python model server (e.g., MLflow, BentoML)
    // Option 3: Sidecar container with REST endpoint

    score, err := s.client.Predict(ctx, features)
    if err != nil {
        return 0, fmt.Errorf("ml prediction: %w", err)
    }

    return score, nil // 0.0 = safe, 1.0 = fraud
}
```

### Threshold Tuning

```
Confusion Matrix for Fraud Detection:

                    Actual Fraud    Actual Legit
Predicted Fraud      True Positive  False Positive  ← False positive rate matters
Predicted Legit      False Negative True Negative   ← False negative rate = bad

For our platform:
- BLOCK at score > 0.95 (high precision, block clear fraud)
- CHALLENGE at score 0.70-0.95 (step-up auth)
- FLAG at score 0.50-0.70 (manual review)
- ALLOW at score < 0.50
```

---

## 5. Operational Considerations

### Latency Management

Fraud scoring must complete within **< 100ms** to avoid impacting the payment flow:

```go
// Timeout the fraud check — never block the payment
ctx, cancel := context.WithTimeout(r.Context(), 80*time.Millisecond)
defer cancel()

result := make(chan *FraudDecision, 1)

go func() {
    score := s.fraudService.Evaluate(r.Context(), txn)
    result <- score
}()

select {
case decision := <-result:
    if decision.Action == ActionBlock {
        return http.StatusPaymentRequired, nil // 402
    }
    // Continue processing
case <-ctx.Done():
    // Timeout — ALLOW the transaction but log for post-analysis
    log.Warn("Fraud check timed out, allowing transaction")
}
```

### Human-in-the-Loop

When a transaction is flagged:
1. Transaction is "held" (funds reserved but not transferred)
2. Assigned to fraud analyst queue
3. Analyst reviews: device fingerprint, IP geolocation, transaction history, call user
4. Decision: Approve (release hold), Decline (reverse hold), or Escalate
5. SLA for manual review: < 15 minutes (or auto-release after threshold)

---

## Check Questions

1. What are the 4 main types of payment fraud?
2. What's the difference between pre-transaction and post-transaction fraud detection?
3. If a rule-based engine sees > 10 transactions to the same recipient in 5 minutes, what should it do?
4. Why is ML fraud scoring more useful than rules alone?
5. What happens if the fraud check takes too long (> 100ms)?
6. What are the 4 possible actions a rule engine can output?
7. Why is false positive rate more important than false negative rate?

---

## Next Module

[Module 06 — Settlement & Reconciliation](06-settlement-and-reconciliation.md)

> Fraud detection is a game of probabilities. Get it wrong, and you lose money either way.
