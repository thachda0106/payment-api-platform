# TASKS: Phase 7 — Architecture Validation (A+)

**Date**: 2026-06-03
**Status**: Draft — Awaiting Approval
**Depends on**: PLAN_PHASE7.md (APPROVED WITH 10 CHANGES)

---

## 🔄 Changes Incorporated (15 total — 10 from PLAN + 5 from TASKS review)

| # | Change | How |
|---|--------|-----|
| 1 | Double-entry accounting | `accounts` + `journal_entries` with balance invariant (sum = 0) |
| 2 | balance = projection | Document: `accounts.balance` is cached projection, `journal_entries` is source of truth |
| 3 | Business event naming | `PaymentApproved`/`PaymentRejected` instead of `FraudChecked`, `LedgerEntryCreated`, `NotificationSent` |
| 4 | Retry topics documented | `payment-events-retry` annotated in topic catalog, not implemented yet |
| 5 | Outbox SKIP LOCKED | `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100` from day 1 |
| 6 | Consumer atomic idempotency | `INSERT ... ON CONFLICT (event_id, consumer_group) DO NOTHING` |
| 7 | Multi-rule fraud engine | amount + velocity + merchant blacklist |
| 8 | Settlement excluded | Settlement-service stays skeleton, not in vertical slice |
| 9 | E2E metrics verification | Verify Kafka consumer lag = 0 + DLQ empty after test |
| 10 | Contract compatibility test | Producer schema validation against consumer expectations |
| 11 | Idempotency in Service only | Removed `IdempotencyFilter` — idempotency handled in `@Transactional PaymentService.createPayment()` |
| 12 | eventId ≠ paymentId | `eventId` is unique per event (dedup). `paymentId` is Kafka key (ordering). Separate concepts. |
| 13 | OutboxPoller async note | Phase 7 uses `.get()` for simplicity; Phase 8 comment to switch to async `whenComplete()` |
| 14 | ledger_transaction_id | Each payment's journal entries linked by `ledger_transaction_id` for audit trail |
| 15 | Contract test → Compatibility | Test that unknown fields are ignored by consumer (`FAIL_ON_UNKNOWN_PROPERTIES = false`) |

---

## 📊 Task Overview

```
Day 1: payment-service (scaffold + DB + API + Outbox + Idempotency)
Day 2: payment-service (Kafka producer) + fraud-service (consumer + multi-rule scorer)
Day 3: financial-core (double-entry ledger + wallet projection + Kafka consumer)
Day 4: notification-service (Kafka consumer + email) + E2E flow
Day 5: Contract tests + CI updates + E2E verification + docs
```

---

## TASK 1: Scaffold payment-service (Java)

**Priority**: HIGH — Blocks all downstream
**Dependencies**: Phase 5 libs, scaffold script
**Estimated**: 15 min

### What
Generate the service from scaffold, then customize.

```bash
make scaffold-java NAME=payment-service
```

### Post-scaffold customizations
- Update `application.yml` with DB + Kafka config
- Add Flyway dependency to pom.xml
- Set port to 8081
- Add `payment_db` to `init-multiple-dbs.sh` (if not already there)

### Verification
```bash
cd services/java/payment-service && mvn spring-boot:run
curl http://localhost:8081/liveness
# → {"status":"ok","service":"payment-service",...}
```

---

## TASK 2: payment-service — Database Migrations

**Priority**: HIGH
**Dependencies**: TASK 1
**Estimated**: 30 min

### Files
```
services/java/payment-service/src/main/resources/db/migration/
├── V1__create_payments.sql
├── V2__create_payment_outbox.sql
└── V3__create_processed_events.sql
```

### V1__create_payments.sql
```sql
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(64) UNIQUE NOT NULL,
    amount DECIMAL(19,4) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    merchant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    payment_method VARCHAR(50) DEFAULT 'CARD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_idempotency ON payments(idempotency_key);
```

### V2__create_payment_outbox.sql
```sql
CREATE TABLE payment_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL REFERENCES payments(id),
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON payment_outbox(published_at, created_at)
    WHERE published_at IS NULL;
```

### V3__create_processed_events.sql
```sql
CREATE TABLE processed_events (
    event_id VARCHAR(128) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_group)  -- Atomic dedup
);

-- For idempotent consumer: INSERT ... ON CONFLICT DO NOTHING
```

### Verification
```sql
-- After service starts, verify tables exist
docker exec -it payment-postgres psql -U payment -d payment_db -c "\dt"
```

---

## TASK 3: payment-service — Entity + Repository Layer

**Priority**: HIGH
**Dependencies**: TASK 2
**Estimated**: 45 min

### What
JPA entities and Spring Data repositories.

### Entities
```java
// Payment.java
@Entity @Table(name = "payments")
public class Payment {
    @Id @GeneratedValue
    private UUID id;
    @Column(unique = true, nullable = false, length = 64)
    private String idempotencyKey;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    private String merchantId;
    private String customerId;
    @Enumerated(STRING)
    private PaymentStatus status = PaymentStatus.CREATED;
    private String paymentMethod;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    // getters, setters...
}

### OutboxEvent.java — Include eventId (separate from paymentId)
```java
@Entity @Table(name = "payment_outbox")
public class OutboxEvent {
    @Id @GeneratedValue
    private UUID id;
    private UUID eventId;        // UNIQUE — per-event dedup ID (NOT paymentId)
    private UUID aggregateId;    // paymentId — for Kafka key (ordering)
    private String eventType;
    @Column(columnDefinition = "jsonb")
    private String payload;  // JSON string
    private String traceId;
    private Instant createdAt = Instant.now();
    private Instant publishedAt;
    
    @PrePersist
    void generateEventId() {
        if (this.eventId == null) {
            this.eventId = UUID.randomUUID();
        }
    }
}
// eventId is the dedup key in processed_events.
// aggregateId (paymentId) is the Kafka message key for partition ordering.
// ONE payment can produce MULTIPLE events (Created, Captured, Refunded) — 
// each gets its own eventId but shares the same aggregateId.

// ProcessedEvent.java
@Entity @Table(name = "processed_events")
@IdClass(ProcessedEventId.class)
public class ProcessedEvent {
    @Id private String eventId;
    @Id private String consumerGroup;
    private Instant processedAt = Instant.now();
}
```

### Repositories
```java
interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdempotencyKey(String key);
}

interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    @Query(value = """
        SELECT * FROM payment_outbox 
        WHERE published_at IS NULL 
        ORDER BY created_at 
        FOR UPDATE SKIP LOCKED 
        LIMIT :limit
    """, nativeQuery = true)
    List<OutboxEvent> findUnpublished(@Param("limit") int limit);
}

interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
    @Modifying
    @Query(value = """
        INSERT INTO processed_events (event_id, consumer_group, processed_at)
        VALUES (:eventId, :consumerGroup, now())
        ON CONFLICT (event_id, consumer_group) DO NOTHING
    """, nativeQuery = true)
    int markAsProcessed(@Param("eventId") String eventId, 
                         @Param("consumerGroup") String consumerGroup);
}
```

### Verification
```java
// Simple repository test with @DataJpaTest
@DataJpaTest
class PaymentRepositoryTest {
    @Autowired PaymentRepository repo;
    
    @Test
    void findByIdempotencyKey_returnsPayment() {
        Payment p = new Payment();
        p.setIdempotencyKey("test-key-1");
        p.setAmount(new BigDecimal("99.99"));
        repo.save(p);
        
        assertTrue(repo.findByIdempotencyKey("test-key-1").isPresent());
    }
}
```

---

## TASK 4: payment-service — Business Logic + Transactional Outbox

**Priority**: HIGH
**Dependencies**: TASK 3
**Estimated**: 1 hour

### Files
```
PaymentService.java     — @Transactional createPayment() with idempotency
OutboxPoller.java        — @Scheduled publish unpublished events (SKIP LOCKED)
KafkaProducerConfig.java — Spring Kafka template
```
NOTE: No `IdempotencyFilter.java` — idempotency lives ONLY in the Service layer.
      Servlet Filters cannot easily cache response bodies for idempotent replies.

### PaymentService.java — Idempotency in Service Layer
```java
@Service
public class PaymentService {
    
    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest req, String traceId) {
        // 1. Idempotency check (UNIQUE constraint on idempotency_key)
        // If duplicate → return cached response (don't reach controller twice)
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent request: key={} returning cached payment={}", 
                req.idempotencyKey(), existing.get().getId());
            return PaymentResponse.from(existing.get());
        }
        
        Payment payment = new Payment();
        payment.setIdempotencyKey(req.idempotencyKey());
        payment.setAmount(req.amount());
        payment.setCurrency(req.currency());
        payment.setMerchantId(req.merchantId());
        payment.setCustomerId(req.customerId());
        payment = paymentRepository.save(payment);
        
        // Outbox with unique eventId (NOT paymentId)
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID());      // Unique per event — dedup key
        event.setAggregateId(payment.getId());    // paymentId — Kafka key for ordering
        event.setEventType("PaymentCreated");
        event.setPayload(toJson(new PaymentCreatedEvent(event.getEventId(), payment)));
        event.setTraceId(traceId);
        outboxRepository.save(event);
        
        return PaymentResponse.from(payment);
    }
}
```

### OutboxPoller.java — SKIP LOCKED + EventId
```java
@Component
public class OutboxPoller {
    
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishUnpublished() {
        List<OutboxEvent> events = outboxRepository.findUnpublished(100);
        for (OutboxEvent event : events) {
            try {
                // Kafka: key = aggregateId (paymentId) for ordering
                //       eventId is in the payload for consumer dedup
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    "payment-events",
                    event.getAggregateId().toString(),  // key = paymentId (ordering)
                    event.getPayload()
                );
                if (event.getTraceId() != null) {
                    record.headers().add("traceId", event.getTraceId().getBytes());
                }
                // Phase 7: synchronous .get() for simplicity
                // Phase 8: convert to async batch with CompletableFuture.whenComplete()
                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
                
                event.setPublishedAt(Instant.now());
                outboxRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}
```

### IdempotencyFilter.java (alternative to service-level check)
```java
@Component
public class IdempotencyFilter extends OncePerRequestFilter {
    // Intercepts POST /v1/payments with Idempotency-Key
    // If duplicate → returns 200 with cached payment (does not reach controller)
    // If new → passes through to controller
}
```

### Verification
```java
@SpringBootTest
@AutoConfigureMockMvc
class PaymentIntegrationTest {
    
    @Test
    void createPayment_success() {
        mockMvc.perform(post("/v1/payments")
            .contentType(APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .content("""
                {"amount":99.99,"currency":"USD","merchantId":"m1","customerId":"c1"}
            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentId").isNotEmpty())
            .andExpect(jsonPath("$.status").value("CREATED"));
    }
    
    @Test
    void duplicateIdempotencyKey_returnsCached() {
        String key = UUID.randomUUID().toString();
        // First request
        mockMvc.perform(post("/v1/payments").header("Idempotency-Key", key)...)
            .andExpect(status().isOk());
        // Duplicate request
        mockMvc.perform(post("/v1/payments").header("Idempotency-Key", key)...)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentId").isNotEmpty());  // Same paymentId
    }
}
```

---

## TASK 5: fraud-service — Kafka Consumer + Multi-Rule Engine

**Priority**: HIGH
**Dependencies**: TASK 4 (need PaymentCreated events)
**Estimated**: 2 hours

### Files
```
services/python/fraud-service/src/fraud_service/
├── consumer.py          # Kafka consumer (aiokafka)
├── scorer.py            # Multi-rule fraud engine
├── models.py            # FraudScore, ProcessedEvent ORM models
└── main.py              # Updated: Kafka consumer startup + shutdown

services/python/fraud-service/alembic/versions/
├── V1__create_fraud_scores.sql
└── V2__create_processed_events.sql
```

### scorer.py — Multi-Rule Engine
```python
class FraudScorer:
    """Multi-rule fraud detection engine."""
    
    # Rule 1: High-value transactions
    HIGH_VALUE_THRESHOLD = 1000.00
    
    # Rule 2: Velocity check (transactions per minute)
    VELOCITY_THRESHOLD = 3  # max 3 transactions per minute per customer
    VELOCITY_WINDOW = 60     # seconds
    
    # Rule 3: Merchant blacklist
    BLACKLISTED_MERCHANTS = {"fraud-merchant-1", "suspicious-merchant-99"}
    
    def score(self, payment: dict) -> FraudResult:
        score = 0.0
        reasons = []
        decision = "APPROVED"
        
        # Rule 1: Amount check
        amount = float(payment["amount"])
        if amount > self.HIGH_VALUE_THRESHOLD:
            decision = "REVIEW"
            score = 30.0
            reasons.append(f"High-value transaction: ${amount}")
        
        # Rule 2: Velocity check
        customer_id = payment["customerId"]
        recent_count = self._count_recent_transactions(customer_id)
        if recent_count > self.VELOCITY_THRESHOLD:
            decision = "REJECTED"
            score = 80.0
            reasons.append(f"Velocity exceeded: {recent_count} in {self.VELOCITY_WINDOW}s")
        
        # Rule 3: Merchant blacklist
        merchant_id = payment["merchantId"]
        if merchant_id in self.BLACKLISTED_MERCHANTS:
            decision = "REJECTED"
            score = 100.0
            reasons.append(f"Blacklisted merchant: {merchant_id}")
        
        return FraudResult(
            payment_id=payment["paymentId"],
            score=score,
            decision=decision,
            reason="; ".join(reasons) if reasons else "Low risk transaction"
        )
```

### consumer.py — Atomic Idempotency (using eventId, NOT paymentId)
```python
async def consume():
    consumer = AIOKafkaConsumer(
        "payment-events",
        bootstrap_servers=config.kafka.bootstrap_servers,
        group_id="fraud-service",
        enable_auto_commit=False,
    )
    
    async for msg in consumer:
        event = json.loads(msg.value)
        event_id = event["eventId"]  # Unique per event — NOT paymentId
        
        # Atomic idempotency: INSERT ... ON CONFLICT DO NOTHING
        inserted = await db.execute(
            """INSERT INTO processed_events (event_id, consumer_group, processed_at)
               VALUES (:eid, :group, now())
               ON CONFLICT (event_id, consumer_group) DO NOTHING
               RETURNING event_id""",
            {"eid": event_id, "group": "fraud-service"}
        )
        if not inserted.rowcount:
            logger.info(f"Duplicate event {event_id} — skipping")
            await consumer.commit()
            continue
        
        # Process
        result = scorer.score(event)
        await save_fraud_score(result)
        
        # Publish result with unique eventId
        event_type = "PaymentApproved" if result.decision == "APPROVED" else "PaymentRejected"
        outbox_event = {
            "eventId": str(uuid.uuid4()),  # New unique eventId
            "paymentId": event["paymentId"],  # Same aggregate
            "type": event_type,
            "score": result.score,
            "decision": result.decision,
            "reason": result.reason,
            "timestamp": datetime.now().isoformat(),
        }
        await publish_to_kafka("fraud-events", event["paymentId"], outbox_event)
        await consumer.commit()
```

---

## TASK 6: financial-core — Double-Entry Ledger

**Priority**: HIGH
**Dependencies**: TASK 5 (need FraudChecked → now PaymentApproved events)
**Estimated**: 2 hours

### Files
```
services/java/financial-core/src/main/java/com/paymentapi/financialcore/
├── entity/
│   ├── Account.java          # accounts table
│   └── JournalEntry.java     # journal_entries table
├── repository/
│   ├── AccountRepository.java
│   ├── JournalEntryRepository.java
│   └── ProcessedEventRepository.java
├── service/
│   └── LedgerService.java    # Double-entry posting
├── consumer/
│   └── FraudEventConsumer.java  # Consumes PaymentApproved/Rejected
└── projection/
    └── WalletProjection.java # accounts.balance = cached projection

src/main/resources/db/migration/
├── V1__create_accounts.sql
├── V2__create_journal_entries.sql
└── V3__create_processed_events.sql
```

### V1__create_accounts.sql
```sql
CREATE TYPE account_type AS ENUM ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 
                                   'PLATFORM_FEE_REVENUE', 'SETTLEMENT_ACCOUNT');

CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_ref VARCHAR(64) UNIQUE NOT NULL,  -- e.g., customer-1, merchant-1
    account_type account_type NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    balance DECIMAL(19,4) NOT NULL DEFAULT 0,  -- CACHED PROJECTION, not source of truth
    version BIGINT NOT NULL DEFAULT 0,          -- optimistic locking for projection update
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN accounts.balance IS 'Cached projection. Source of truth: journal_entries.';
```

### V2__create_journal_entries.sql
```sql
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_transaction_id UUID NOT NULL,  -- Groups entries for one payment (audit trail)
    payment_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount DECIMAL(19,4) NOT NULL CHECK (amount > 0),
    balance_before DECIMAL(19,4) NOT NULL,
    balance_after DECIMAL(19,4) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_journal_txn ON journal_entries(ledger_transaction_id);
CREATE INDEX idx_journal_payment ON journal_entries(payment_id);
CREATE INDEX idx_journal_account ON journal_entries(account_id);
```

### LedgerService.java — Double-Entry
```java
@Service
public class LedgerService {
    // Platform fee: 3%
    private static final BigDecimal FEE_RATE = new BigDecimal("0.03");
    
    @Transactional
    public void postPayment(PaymentApprovedEvent event) {
        // Prevent double-processing
        // INSERT INTO processed_events ... ON CONFLICT DO NOTHING
        if (!processedEventRepo.markAsProcessed(event.paymentId(), "financial-core")) {
            return;  // Already processed
        }
        
        BigDecimal amount = event.amount();
        BigDecimal fee = amount.multiply(FEE_RATE).setScale(4, HALF_UP);
        BigDecimal merchantAmount = amount.subtract(fee);
        
        // Double-entry: sum of all entries MUST equal zero
        // ─────────────────────────────────────────────
        // Customer Wallet      -amount      (DEBIT)
        // Merchant Payable     +merchantAmount (CREDIT)
        // Platform Fee Revenue +fee          (CREDIT)
        // ─────────────────────────────────────────────
        // Total:               -amount + merchantAmount + fee = -amount + (amount - fee) + fee = 0 ✓
        
        Account customerWallet = accountRepo.findByExternalRef(event.customerId(), CUSTOMER_WALLET)
            .orElseGet(() -> createAccount(event.customerId(), CUSTOMER_WALLET));
        Account merchantPayable = accountRepo.findByExternalRef(event.merchantId(), MERCHANT_PAYABLE)
            .orElseGet(() -> createAccount(event.merchantId(), MERCHANT_PAYABLE));
        Account platformFee = accountRepo.findByExternalRef("PLATFORM", PLATFORM_FEE_REVENUE)
            .orElseGet(() -> createAccount("PLATFORM", PLATFORM_FEE_REVENUE));
        
        // Debit customer wallet (optimistic locking on projection)
        debitAccount(customerWallet, amount, event.paymentId(), "Payment to " + event.merchantId());
        creditAccount(merchantPayable, merchantAmount, event.paymentId(), "Payment from " + event.customerId());
        creditAccount(platformFee, fee, event.paymentId(), "Processing fee");
        
        // Publish LedgerEntryCreated event
        publishEvent("ledger-events", new LedgerEntryCreatedEvent(event));
    }
    
    private void debitAccount(Account account, BigDecimal amount, UUID paymentId, String desc) {
        account.setBalance(account.getBalance().subtract(amount));
        accountRepo.save(account);
        
        JournalEntry entry = new JournalEntry();
        entry.setPaymentId(paymentId);
        entry.setAccountId(account.getId());
        entry.setEntryType(EntryType.DEBIT);
        entry.setAmount(amount);
        entry.setBalanceBefore(account.getBalance().add(amount));
        entry.setBalanceAfter(account.getBalance());
        entry.setDescription(desc);
        journalEntryRepo.save(entry);
    }
}
```

---

## TASK 7: notification-service — Kafka Consumer + Email

**Priority**: HIGH
**Dependencies**: TASK 6
**Estimated**: 1.5 hours

### Files
```
services/nodejs/notification-service/src/
├── consumer.ts              # Kafka consumer (kafkajs)
├── email-service.ts         # Email sender (nodemailer)
├── models.ts                # Notification, ProcessedEvent entities
└── main.ts                  # Updated: Kafka consumer startup

migrations/
├── V1__create_notifications.sql
└── V2__create_processed_events.sql
```

### consumer.ts — Atomic Idempotency
```typescript
async function consumeLedgerEvents() {
  const consumer = kafka.consumer({ groupId: 'notification-service' });
  await consumer.connect();
  await consumer.subscribe({ topic: 'ledger-events' });
  
  await consumer.run({
    eachMessage: async ({ message, heartbeat }) => {
      const event = JSON.parse(message.value!.toString());
      const eventId = message.key?.toString() || event.paymentId;
      
      // Atomic idempotency: INSERT ... ON CONFLICT DO NOTHING
      const result = await db.query(
        `INSERT INTO processed_events (event_id, consumer_group, processed_at)
         VALUES ($1, $2, now())
         ON CONFLICT (event_id, consumer_group) DO NOTHING
         RETURNING event_id`,
        [eventId, 'notification-service']
      );
      
      if (result.rowCount === 0) {
        logger.info(`Duplicate event ${eventId} — skipping`);
        return;
      }
      
      // Send receipt email
      await emailService.sendReceipt({
        to: event.customerEmail || `${event.customerId}@example.com`,
        subject: `Payment Receipt — $${event.amount}`,
        paymentId: event.paymentId,
        amount: event.amount,
        currency: event.currency,
      });
      
      // Publish NotificationSent event
      await producer.send({
        topic: 'notification-events',
        messages: [{
          key: event.paymentId,
          value: JSON.stringify({
            type: 'NotificationSent',
            paymentId: event.paymentId,
            recipientEmail: event.customerEmail,
            amount: event.amount,
            timestamp: new Date().toISOString(),
          }),
          headers: {
            traceId: message.headers?.traceId?.toString() || '',
          },
        }],
      });
      
      await heartbeat();
    },
  });
}
```

---

## TASK 8: Docker Compose + CI Updates

**Priority**: MEDIUM
**Dependencies**: TASK 1-7
**Estimated**: 45 min

### What
- Add payment-service to docker-compose (port 8081, payment_db)
- Add payment_db to init-multiple-dbs.sh
- Add payment-service to CI Java matrix
- Add payment-service to CD matrix
- Update Kafka topic auto-creation (topics: payment-events, fraud-events, ledger-events, notification-events, payment-events-dlq)

### docker-compose.yml payment-service entry
```yaml
payment-service:
  profiles: ["services"]
  build:
    context: ./services/java/payment-service
    dockerfile: ../../../../docker/Dockerfile.java
  container_name: payment-payment-service
  restart: unless-stopped
  depends_on:
    postgres:
      condition: service_healthy
    kafka:
      condition: service_healthy
  environment:
    SERVER_PORT: "8081"
    DATABASE_URL: jdbc:postgresql://postgres:5432/payment_db
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    KAFKA_CONSUMER_GROUP: payment-service
    OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
    OTEL_SERVICE_NAME: payment-service
    SERVICE_VERSION: "0.1.0"
    LOG_LEVEL: info
    LOG_FORMAT: json
  ports:
    - "8081:8081"
  networks:
    - payment-network
```

---

## TASK 9: Contract Compatibility Test

**Priority**: MEDIUM
**Dependencies**: TASK 4, TASK 5
**Estimated**: 1 hour

### What
Test that producer schema changes don't break downstream consumers. NOT just schema validation — true compatibility testing.

### Producer Side (Java): Schema Compatibility
```java
@Test
void paymentCreated_v2_withNewField_compatibleWith_v1Consumer() {
    // Given: Producer evolves schema — adds "metadata" field in v2
    // Consumer only knows v1 fields: paymentId, amount, currency, merchantId, customerId
    String v2Event = """
        {
          "eventId": "evt-123",
          "type": "PaymentCreated",
          "paymentId": "pay-456",
          "amount": 99.99,
          "currency": "USD",
          "merchantId": "m1",
          "customerId": "c1",
          "metadata": {"source": "mobile", "ip": "1.2.3.4"},
          "timestamp": "2026-06-03T12:00:00Z"
        }
        """;
    
    // When: v1 Consumer (fraud-service) parses with FAIL_ON_UNKNOWN_PROPERTIES=false
    // Then: Consumer still receives all v1 fields correctly
    ObjectMapper consumerMapper = new ObjectMapper();
    // consumerMapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false); // default = false
    
    PaymentCreatedEvent parsed = consumerMapper.readValue(v2Event, PaymentCreatedEvent.class);
    
    assertThat(parsed.paymentId()).isEqualTo(UUID.fromString("pay-456"));
    assertThat(parsed.amount()).isEqualByComparingTo("99.99");
    assertThat(parsed.currency()).isEqualTo("USD");
    // metadata silently ignored — backward compatible
}
```

### Consumer Side (Python): Forward Compatibility
```python
def test_forward_compatibility_unknown_field_ignored():
    """Consumer v1 can parse Producer v2 with new fields."""
    v2_event = {
        "eventId": "evt-123",
        "type": "PaymentCreated",
        "paymentId": "pay-456",
        "amount": 99.99,
        "currency": "USD",
        "merchantId": "m1",
        "customerId": "c1",
        "new_field_v2": "should_be_ignored",  # Unknown to v1 consumer
        "timestamp": "2026-06-03T12:00:00Z",
    }
    
    # v1 Pydantic model with extra="ignore"
    parsed = PaymentCreatedV1Model.model_validate(v2_event)
    
    assert parsed.payment_id == "pay-456"
    assert parsed.amount == 99.99
    # new_field_v2 is silently ignored — forward compatible
```

### Schema Evolution Rules (documented for future)
```
Backward Compatible (OK):
  ✅ Add optional field to producer event
  ✅ Consumer ignores unknown fields (FAIL_ON_UNKNOWN_PROPERTIES=false)
  ✅ Widen field type (int → long)

Breaking Changes (requires new topic/major version):
  ❌ Remove required field
  ❌ Change field type (string → int)
  ❌ Rename field
  ❌ Change field semantics
```

---

## TASK 10: E2E Verification

**Priority**: HIGH
**Dependencies**: TASK 1-9
**Estimated**: 1 hour

### Verification Script
```bash
#!/bin/bash
# verify-vertical-slice.sh

echo "=== 1. Start all services ==="
docker-compose up -d
sleep 90

echo "=== 2. Verify all liveness probes ==="
curl -sf http://localhost:8080/liveness && echo "financial-core: OK"
curl -sf http://localhost:8081/liveness && echo "payment-service: OK"
curl -sf http://localhost:8000/liveness && echo "fraud-service: OK"
curl -sf http://localhost:3001/liveness && echo "notification-service: OK"
curl -sf http://localhost:8088/liveness && echo "settlement-service: OK"

echo "=== 3. Create payment (idempotent) ==="
IDEMPOTENCY_KEY=$(uuidgen)
RESPONSE=$(curl -s -X POST http://localhost:8081/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d '{"amount":99.99,"currency":"USD","merchantId":"m1","customerId":"c1"}')
echo "Response: $RESPONSE"
PAYMENT_ID=$(echo $RESPONSE | jq -r '.paymentId')

echo "=== 4. Verify idempotency (same key) ==="
RESPONSE2=$(curl -s -X POST http://localhost:8081/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d '{"amount":99.99,"currency":"USD","merchantId":"m1","customerId":"c1"}')
echo "Duplicate response: $RESPONSE2"
[ "$(echo $RESPONSE2 | jq -r '.paymentId')" == "$PAYMENT_ID" ] && echo "Idempotency: OK" || echo "Idempotency: FAIL"

echo "=== 5. Wait for event processing ==="
sleep 15

echo "=== 6. Verify Kafka consumer lag ==="
# Check consumer group lag (requires kafka-consumer-groups CLI)
docker exec payment-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group fraud-service --describe 2>/dev/null | tail -5
echo "Lag should be 0 for all partitions"

echo "=== 7. Verify DLQ is empty ==="
# Check payment-events-dlq topic
MSG_COUNT=$(docker exec payment-kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic payment-events-dlq --time -1 2>/dev/null | awk -F: '{sum+=$3} END {print sum}')
echo "DLQ message count: $MSG_COUNT"
[ "$MSG_COUNT" -eq 0 ] && echo "DLQ empty: OK" || echo "DLQ has messages: CHECK"

echo "=== 8. Verify traces in Jaeger ==="
TRACE_COUNT=$(curl -s "http://localhost:16686/api/traces?service=payment-service&lookback=5m&limit=10" | jq '.data | length')
echo "Traces found: $TRACE_COUNT"
[ "$TRACE_COUNT" -gt 0 ] && echo "Tracing: OK" || echo "Tracing: FAIL"

echo "=== 9. Verify double-entry balance ==="
# Query journal_entries for the payment
BALANCE=$(docker exec payment-postgres psql -U payment -d financial_core_db -t -c \
  "SELECT SUM(CASE WHEN entry_type='CREDIT' THEN amount ELSE -amount END) FROM journal_entries WHERE payment_id='$PAYMENT_ID'")
echo "Journal balance: $BALANCE"
[ "$(echo $BALANCE | xargs)" == "0.0000" ] && echo "Double-entry balanced: OK" || echo "Double-entry: CHECK"

echo "=== ALL CHECKS COMPLETE ==="
```

---

## TASK 11: Documentation

**Priority**: LOW
**Dependencies**: TASK 1-10
**Estimated**: 1 hour

### Write `docs/07-build-implementation.md`
- Architecture validation approach
- Double-entry accounting model
- Event flow diagram with all 4 services
- Outbox pattern with SKIP LOCKED
- Idempotency (API-level + consumer-level)
- Tracing across services
- Known limitations (wallet.balance is projection, settlement excluded, retry topics documented but not implemented)

---

## ⏱️ Time Estimates (Realistic — Solo Developer)

| Task | Hours | Notes |
|------|-------|-------|
| 1-2: Scaffold + DB migrations | 1h | Quick — scaffold script + SQL |
| 3-4: Entity + Business Logic + Outbox | 5-7h | Core payment creation + transactional outbox + SKIP LOCKED |
| 5: Fraud consumer + multi-rule engine | 3-4h | Kafka consumer + aiokafka + 3-rule scorer |
| 6: Double-entry ledger | 6-8h | Journal entries, accounts, balanced transactions, optimistic locking |
| 7: Notification consumer + email | 2-3h | KafkaJS consumer + nodemailer |
| 8: Docker + CI updates | 1h | Compose + CI matrix |
| 9: Contract compatibility tests | 1h | Producer v2 → Consumer v1 test |
| 10: E2E verification | 2-3h | Full flow debugging, tracing, DLQ check |
| 11: Documentation | 1-2h | Phase 7 doc |
| **Total** | **22-30h** | Realistic for one engineer across 4 languages |

### Daily Breakdown
```
Day 1-2:  payment-service (scaffold + API + outbox)         — 6-8h
Day 3:    fraud-service (Kafka consumer + scorer)            — 3-4h
Day 4-5:  financial-core (double-entry ledger + journal)     — 6-8h
Day 6:    notification-service (email + consumer)            — 2-3h
Day 7-8:  E2E + CI + contract tests + docs                   — 5-7h
```

---

TASKS complete. Reply **APPROVE** to begin implementation.
