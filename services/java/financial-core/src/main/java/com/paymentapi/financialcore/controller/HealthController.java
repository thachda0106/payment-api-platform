package com.paymentapi.financialcore.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Backward-compatible redirects from old health paths.
 * Delegate to platform-libs probe controllers for actual logic.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Void> healthRedirect() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
            .location(URI.create("/liveness"))
            .build();
    }

    @GetMapping("/ready")
    public ResponseEntity<Void> readyRedirect() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
            .location(URI.create("/readiness"))
            .build();
    }
}
