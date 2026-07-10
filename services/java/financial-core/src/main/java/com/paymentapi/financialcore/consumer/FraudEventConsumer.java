package com.paymentapi.financialcore.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentapi.financialcore.entity.LedgerOutboxEvent;
import com.paymentapi.financialcore.metrics.OutboxMetrics;
import com.paymentapi.financialcore.repository.LedgerOutboxRepository;
import com.paymentapi.financialcore.repository.ProcessedEventRepository;
import com.paymentapi.financialcore.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Consumes PaymentApproved/PaymentRejected events from the `fraud-events` topic.
 *
 * The dedup mark, ledger journal entries, and the ledger-events outbox row are all
 * written in a SINGLE transaction (crash-safe idempotency + no dual-write). The
 * LedgerOutboxPoller drains the outbox to Kafka.
 */
@Component
public class FraudEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(FraudEventConsumer.class);
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final String CONSUMER_GROUP = "financial-core";
    private static final String DLQ_TOPIC = "fraud-events-dlq";

    private final LedgerService ledgerService;
    private final ProcessedEventRepository processedEventRepo;
    private final LedgerOutboxRepository ledgerOutboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxMetrics metrics;
    private final ObjectMapper mapper;

    public FraudEventConsumer(LedgerService ledgerService,
                              ProcessedEventRepository processedEventRepo,
                              LedgerOutboxRepository ledgerOutboxRepo,
                              KafkaTemplate<String, String> kafkaTemplate,
                              OutboxMetrics metrics,
                              ObjectMapper mapper) {
        this.ledgerService = ledgerService;
        this.processedEventRepo = processedEventRepo;
        this.ledgerOutboxRepo = ledgerOutboxRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "fraud-events", groupId = CONSUMER_GROUP)
    @Transactional
    public void consume(String message) {
        JsonNode event;
        try {
            event = mapper.readTree(message);
        } catch (Exception e) {
            log.error("Unparseable fraud event → DLQ: {}", e.getMessage());
            sendToDlq(message, "unparseable: " + e.getMessage());
            return;
        }

        String validationError = validate(event);
        if (validationError != null) {
            log.error("Invalid fraud event → DLQ: {}", validationError);
            sendToDlq(message, validationError);
            return;
        }

        String eventId = event.get("eventId").asText();

        // Atomic idempotency — shares this @Transactional with the ledger + outbox writes.
        if (!processedEventRepo.markAsProcessed(eventId, CONSUMER_GROUP)) {
            log.debug("Duplicate event {} — skipping", eventId);
            return;
        }

        String decision = event.get("decision").asText();
        UUID paymentId = UUID.fromString(event.get("paymentId").asText());

        if (!"APPROVED".equals(decision)) {
            log.info("Payment {} not approved ({}) — no ledger entry", paymentId, decision);
            return;
        }

        // Real business identifiers — propagated by fraud-service (A1).
        String customerId = event.get("customerId").asText();
        String merchantId = event.get("merchantId").asText();
        BigDecimal amount = new BigDecimal(event.get("amount").asText());
        String currency = event.get("currency").asText();

        UUID ledgerTxnId = ledgerService.postPayment(paymentId, customerId, merchantId, amount)
            .orElse(null);
        if (ledgerTxnId == null) {
            log.warn("Ledger already posted for payment {} — no new ledger-event emitted", paymentId);
            return;
        }

        writeOutbox(paymentId, ledgerTxnId, customerId, merchantId, amount, currency);
        log.info("Ledger posted for payment {} — txn {}", paymentId, ledgerTxnId);
    }

    private void writeOutbox(UUID paymentId, UUID ledgerTxnId, String customerId,
                             String merchantId, BigDecimal amount, String currency) {
        UUID outEventId = UUID.randomUUID();
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.ofEntries(
                Map.entry("v", 1),
                Map.entry("eventId", outEventId.toString()),
                Map.entry("type", "LedgerEntryCreated"),
                Map.entry("paymentId", paymentId.toString()),
                Map.entry("ledgerTransactionId", ledgerTxnId.toString()),
                Map.entry("customerId", customerId),
                Map.entry("merchantId", merchantId),
                Map.entry("amount", amount.toPlainString()),
                Map.entry("currency", currency),
                Map.entry("timestamp", Instant.now().toString())
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ledger event payload", e);
        }

        LedgerOutboxEvent outbox = new LedgerOutboxEvent();
        outbox.setEventId(outEventId);
        outbox.setAggregateId(paymentId);
        outbox.setEventType("LedgerEntryCreated");
        outbox.setPayload(payload);
        ledgerOutboxRepo.save(outbox);
    }

    private void sendToDlq(String rawMessage, String reason) {
        try {
            String dlqPayload = mapper.writeValueAsString(Map.of(
                "error", reason,
                "consumer", CONSUMER_GROUP,
                "timestamp", Instant.now().toString(),
                "original", rawMessage
            ));
            kafkaTemplate.send(DLQ_TOPIC, dlqPayload);
            metrics.incrementDlq();
        } catch (Exception e) {
            log.error("Failed to send to DLQ: {}", e.getMessage());
        }
    }

    /** Returns an error description if the event violates the contract, else null. */
    private String validate(JsonNode e) {
        if (isBlank(e, "eventId")) return "missing eventId";
        if (isBlank(e, "paymentId")) return "missing paymentId";
        try {
            UUID.fromString(e.get("paymentId").asText());
        } catch (Exception ex) {
            return "paymentId not a UUID";
        }
        if (isBlank(e, "decision")) return "missing decision";
        if ("APPROVED".equals(e.path("decision").asText())) {
            if (isBlank(e, "customerId")) return "missing customerId";
            if (isBlank(e, "merchantId")) return "missing merchantId";
            if (isBlank(e, "currency") || !CURRENCY.matcher(e.get("currency").asText()).matches())
                return "currency not ISO 4217";
            if (isBlank(e, "amount")) return "missing amount";
            try {
                if (new BigDecimal(e.get("amount").asText()).signum() <= 0) return "amount must be > 0";
            } catch (NumberFormatException ex) {
                return "amount not decimal";
            }
        }
        return null;
    }

    private boolean isBlank(JsonNode e, String field) {
        JsonNode n = e.get(field);
        return n == null || n.isNull() || n.asText().isBlank();
    }
}
