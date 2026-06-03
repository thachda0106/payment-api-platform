package com.paymentapi.paymentservice.controller;

import com.paymentapi.paymentservice.dto.CreatePaymentRequest;
import com.paymentapi.paymentservice.dto.PaymentResponse;
import com.paymentapi.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey) {

        String traceId = MDC.get("traceId");
        PaymentResponse response = paymentService.createPayment(request, idempotencyKey, traceId);

        return ResponseEntity
            .created(URI.create("/v1/payments/" + response.paymentId()))
            .body(response);
    }
}
