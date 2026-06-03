package com.paymentapi.platform.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Backward-compatible redirects for old probe paths.
 * {@code /health} → 301 → {@code /liveness}
 * {@code /ready}  → 301 → {@code /readiness}
 *
 * <p>These endpoints will be removed in Phase 7.
 */
@RestController
public class LegacyProbeController {

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
