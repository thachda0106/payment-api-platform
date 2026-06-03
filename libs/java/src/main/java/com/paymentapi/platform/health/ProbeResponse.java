package com.paymentapi.platform.health;

import java.time.Instant;
import java.util.Map;

/**
 * Standardized response for all probe endpoints
 * ({@code /liveness}, {@code /readiness}, {@code /startup}).
 */
public record ProbeResponse(
    String status,
    String service,
    String version,
    Instant timestamp,
    double uptime,
    Map<String, CheckResult> checks
) {
    public static ProbeResponse ok(String service, String version, double uptime,
                                    Map<String, CheckResult> checks) {
        return new ProbeResponse("ok", service, version, Instant.now(), uptime, checks);
    }

    public static ProbeResponse down(String service, String version, double uptime,
                                      Map<String, CheckResult> checks) {
        return new ProbeResponse("unhealthy", service, version, Instant.now(), uptime, checks);
    }

    public static ProbeResponse notReady(String service, String version, double uptime,
                                          Map<String, CheckResult> checks) {
        return new ProbeResponse("not_ready", service, version, Instant.now(), uptime, checks);
    }
}
