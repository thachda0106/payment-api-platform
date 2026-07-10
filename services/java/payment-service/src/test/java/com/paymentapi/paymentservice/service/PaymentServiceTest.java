package com.paymentapi.paymentservice.service;

import com.paymentapi.paymentservice.dto.CreatePaymentRequest;
import com.paymentapi.paymentservice.dto.PaymentResponse;
import com.paymentapi.paymentservice.entity.Payment;
import com.paymentapi.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PaymentServiceTest {

    private final PaymentRepository paymentRepo = mock(PaymentRepository.class);
    private final PaymentWriter writer = mock(PaymentWriter.class);
    private final PaymentService service = new PaymentService(paymentRepo, writer);

    private CreatePaymentRequest request() {
        return new CreatePaymentRequest(new BigDecimal("99.99"), "USD", "m1", "c1");
    }

    private Payment payment(UUID id) {
        Payment p = new Payment();
        p.setId(id);
        p.setIdempotencyKey("key-1");
        p.setAmount(new BigDecimal("99.99"));
        p.setCurrency("USD");
        p.setMerchantId("m1");
        p.setCustomerId("c1");
        p.setStatus("CREATED");
        return p;
    }

    @Test
    void returnsCachedPaymentOnFastPath() {
        UUID id = UUID.randomUUID();
        when(paymentRepo.findByIdempotencyKey("key-1")).thenReturn(Optional.of(payment(id)));

        PaymentResponse res = service.createPayment(request(), "key-1", "trace");

        assertThat(res.paymentId()).isEqualTo(id);
        verify(writer, never()).persist(any(), any(), any());
    }

    @Test
    void writesWhenNotSeenBefore() {
        UUID id = UUID.randomUUID();
        when(paymentRepo.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(writer.persist(any(), eq("key-1"), any())).thenReturn(payment(id));

        PaymentResponse res = service.createPayment(request(), "key-1", "trace");

        assertThat(res.paymentId()).isEqualTo(id);
    }

    @Test
    void resolvesIdempotencyRaceByReturningCachedPayment() {
        UUID winner = UUID.randomUUID();
        // Pre-check sees nothing; the concurrent insert then wins and our write violates the unique key;
        // the recovery re-read returns the winner's payment.
        when(paymentRepo.findByIdempotencyKey("key-1"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(payment(winner)));
        when(writer.persist(any(), eq("key-1"), any()))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        PaymentResponse res = service.createPayment(request(), "key-1", "trace");

        assertThat(res.paymentId()).isEqualTo(winner);
        verify(paymentRepo, times(2)).findByIdempotencyKey("key-1");
    }

    @Test
    void rethrowsWhenViolationButNoCachedRowFound() {
        when(paymentRepo.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(writer.persist(any(), eq("key-1"), any()))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThrows(DataIntegrityViolationException.class,
            () -> service.createPayment(request(), "key-1", "trace"));
    }
}
