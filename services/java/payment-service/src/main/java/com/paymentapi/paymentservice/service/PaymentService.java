package com.paymentapi.paymentservice.service;

import com.paymentapi.paymentservice.dto.CreatePaymentRequest;
import com.paymentapi.paymentservice.dto.PaymentResponse;
import com.paymentapi.paymentservice.entity.Payment;
import com.paymentapi.paymentservice.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Core payment creation with idempotency and transactional outbox.
 *
 * Idempotency is race-safe: a fast pre-check handles the common case, and a
 * unique-key violation (concurrent duplicate) is caught and resolved by
 * returning the cached payment (re-read in a fresh transaction).
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepo;
    private final PaymentWriter writer;

    public PaymentService(PaymentRepository paymentRepo, PaymentWriter writer) {
        this.paymentRepo = paymentRepo;
        this.writer = writer;
    }

    public PaymentResponse createPayment(CreatePaymentRequest req, String idempotencyKey, String traceId) {
        // 1. Fast path — already processed.
        Optional<Payment> existing = paymentRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotent request: key={} returning cached payment={}", idempotencyKey, existing.get().getId());
            return PaymentResponse.from(existing.get());
        }

        // 2. Write payment + outbox atomically.
        try {
            Payment payment = writer.persist(req, idempotencyKey, traceId);
            log.info("Payment created: id={} amount={} {}", payment.getId(), req.amount(), req.currency());
            return PaymentResponse.from(payment);
        } catch (DataIntegrityViolationException e) {
            // 3. Lost the idempotency race — another request inserted first.
            //    Re-read in a fresh transaction and return the cached payment.
            log.info("Idempotency race for key={} — returning concurrently-created payment", idempotencyKey);
            return paymentRepo.findByIdempotencyKey(idempotencyKey)
                .map(PaymentResponse::from)
                .orElseThrow(() -> e);
        }
    }
}
