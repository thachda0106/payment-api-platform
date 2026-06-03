package com.paymentapi.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentapi.paymentservice.dto.CreatePaymentRequest;
import com.paymentapi.paymentservice.dto.PaymentResponse;
import com.paymentapi.paymentservice.entity.OutboxEvent;
import com.paymentapi.paymentservice.entity.Payment;
import com.paymentapi.paymentservice.repository.OutboxRepository;
import com.paymentapi.paymentservice.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core payment creation with idempotency and transactional outbox.
 * Idempotency lives here (Service layer), not in a Servlet Filter.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper mapper;

    public PaymentService(PaymentRepository paymentRepo, OutboxRepository outboxRepo, ObjectMapper mapper) {
        this.paymentRepo = paymentRepo;
        this.outboxRepo = outboxRepo;
        this.mapper = mapper;
    }

    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest req, String idempotencyKey, String traceId) {
        // 1. Idempotency: check if already processed (UNIQUE constraint backs this)
        Optional<Payment> existing = paymentRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotent request: key={} returning cached payment={}", idempotencyKey, existing.get().getId());
            return PaymentResponse.from(existing.get());
        }

        // 2. Create payment
        Payment payment = new Payment();
        payment.setIdempotencyKey(idempotencyKey);
        payment.setAmount(req.amount());
        payment.setCurrency(req.currency());
        payment.setMerchantId(req.merchantId());
        payment.setCustomerId(req.customerId());
        payment.setStatus("CREATED");
        payment = paymentRepo.save(payment);

        // 3. Create outbox event (same transaction — both or neither committed)
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID());
        event.setAggregateId(payment.getId());
        event.setEventType("PaymentCreated");
        event.setPayload(toPayload(payment));
        event.setTraceId(traceId);
        outboxRepo.save(event);

        log.info("Payment created: id={} amount={} {}", payment.getId(), req.amount(), req.currency());
        return PaymentResponse.from(payment);
    }

    private String toPayload(Payment p) {
        try {
            return mapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "type", "PaymentCreated",
                "paymentId", p.getId().toString(),
                "amount", p.getAmount(),
                "currency", p.getCurrency(),
                "merchantId", p.getMerchantId(),
                "customerId", p.getCustomerId(),
                "timestamp", Instant.now().toString()
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }
}
