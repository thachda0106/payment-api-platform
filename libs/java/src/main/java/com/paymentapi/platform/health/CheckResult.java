package com.paymentapi.platform.health;

import java.time.Instant;

/**
 * The result of a single dependency check, recorded in the cached registry.
 *
 * @param status     current dependency status
 * @param latencyMs  latency of the last check (ms)
 * @param lastChecked when the check was last performed
 */
public record CheckResult(
    DependencyStatus status,
    double latencyMs,
    Instant lastChecked
) {
    public static CheckResult ok(double latencyMs) {
        return new CheckResult(DependencyStatus.OK, latencyMs, Instant.now());
    }

    public static CheckResult down(double latencyMs) {
        return new CheckResult(DependencyStatus.DOWN, latencyMs, Instant.now());
    }

    public static CheckResult unused() {
        return new CheckResult(DependencyStatus.UNUSED, 0, Instant.now());
    }

    public boolean isHealthy() {
        return status == DependencyStatus.OK || status == DependencyStatus.UNUSED;
    }
}
