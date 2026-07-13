package com.paymentapi.financialcore.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentapi.financialcore.entity.OutboxEvent;
import com.paymentapi.financialcore.repository.InboxRepository;
import com.paymentapi.financialcore.repository.OutboxRepository;
import com.paymentapi.financialcore.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Posts the double-entry ledger for an approved payment and writes the
 * `ledger.entry.committed` CloudEvents envelope to the CDC outbox — plus marks the
 * inbox COMPLETED — all in ONE transaction (crash-safe). Works from the normalized
 * JSON payload stored in the inbox, so live consumption and retry share this path.
 */
@Service
public class LedgerPostingService {
    private static final Logger log = LoggerFactory.getLogger(LedgerPostingService.class);
    private static final String GROUP = "financial-core";
    private static final String EVENT_TYPE = "ledger.entry.committed";
    private static final String EVENT_TOPIC = "ledger.entry.committed";
    private static final BigDecimal FEE_RATE = new BigDecimal("0.03");

    private final LedgerService ledgerService;
    private final OutboxRepository outboxRepo;
    private final InboxRepository inboxRepo;
    private final ObjectMapper mapper;

    public LedgerPostingService(LedgerService ledgerService, OutboxRepository outboxRepo,
                                InboxRepository inboxRepo, ObjectMapper mapper) {
        this.ledgerService = ledgerService;
        this.outboxRepo = outboxRepo;
        this.inboxRepo = inboxRepo;
        this.mapper = mapper;
    }

    /** Normalized payload JSON: {eventId, paymentId, customerId, merchantId, amountMinor, currency}. */
    @Transactional
    public void process(String payloadJson) throws Exception {
        JsonNode p = mapper.readTree(payloadJson);
        String eventId = p.get("eventId").asText();
        UUID paymentId = UUID.fromString(p.get("paymentId").asText());
        String customerId = p.get("customerId").asText();
        String merchantId = p.get("merchantId").asText();
        long amountMinor = p.get("amountMinor").asLong();
        String currency = p.get("currency").asText();

        UUID ledgerTxnId = ledgerService.postPayment(paymentId, customerId, merchantId, amountMinor).orElse(null);
        if (ledgerTxnId != null) {
            outboxRepo.save(buildOutbox(paymentId, ledgerTxnId, customerId, merchantId, amountMinor, currency));
            log.info("Ledger posted for payment {} — txn {}", paymentId, ledgerTxnId);
        } else {
            log.warn("Ledger already posted for payment {} — no new ledger-event", paymentId);
        }
        inboxRepo.markCompleted(eventId, GROUP);
    }

    private OutboxEvent buildOutbox(UUID paymentId, UUID ledgerTxnId, String customerId,
                                    String merchantId, long amountMinor, String currency) {
        UUID eventId = UUID.randomUUID();
        long feeMinor = BigDecimal.valueOf(amountMinor).multiply(FEE_RATE)
            .setScale(0, RoundingMode.HALF_UP).longValueExact();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment_id", paymentId.toString());
        data.put("ledger_transaction_id", ledgerTxnId.toString());
        data.put("customer_id", customerId);
        data.put("merchant_id", merchantId);
        data.put("amount", amountMinor);
        data.put("fee", feeMinor);
        data.put("currency", currency);

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("service", "financial-core");
        trigger.put("instance", "");
        trigger.put("request_id", "");
        trigger.put("idempotency_key", "");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId.toString());
        envelope.put("type", EVENT_TYPE);
        envelope.put("time", Instant.now().toString());
        envelope.put("data", data);
        envelope.put("trigger", trigger);

        String payload;
        try {
            payload = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ledger event payload", e);
        }

        OutboxEvent outbox = new OutboxEvent();
        outbox.setId(eventId);
        outbox.setAggregateType("payment");
        outbox.setAggregateId(paymentId.toString());
        outbox.setEventType(EVENT_TYPE);
        outbox.setEventTopic(EVENT_TOPIC);
        outbox.setPartitionKey(paymentId.toString());
        outbox.setPayload(payload);
        return outbox;
    }
}
