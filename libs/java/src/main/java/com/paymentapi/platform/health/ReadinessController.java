package com.paymentapi.platform.health;

import com.paymentapi.platform.telemetry.TelemetryMetadataProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.Map;

/**
 * GET /readiness — dependency-aware probe.
 * Returns 200 if all registered dependencies are healthy, 503 otherwise.
 * Uses {@link CachedDependencyRegistry} to avoid live I/O on every probe call.
 */
@RestController
public class ReadinessController {

    private final TelemetryMetadataProperties metadata;
    private final CachedDependencyRegistry registry;
    private final double startTime;

    public ReadinessController(TelemetryMetadataProperties metadata,
                                CachedDependencyRegistry registry) {
        this.metadata = metadata;
        this.registry = registry;
        this.startTime = ManagementFactory.getRuntimeMXBean().getStartTime() / 1000.0;
    }

    @GetMapping("/readiness")
    public ResponseEntity<ProbeResponse> readiness() {
        double uptime = (System.currentTimeMillis() / 1000.0) - startTime;
        Map<String, CheckResult> statuses = registry.getStatuses();

        boolean allHealthy = statuses.isEmpty() ||
            statuses.values().stream().allMatch(CheckResult::isHealthy);

        ProbeResponse response = allHealthy
            ? ProbeResponse.ok(metadata.getServiceName(), metadata.getServiceVersion(), uptime, statuses)
            : ProbeResponse.notReady(metadata.getServiceName(), metadata.getServiceVersion(), uptime, statuses);

        return ResponseEntity
            .status(allHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
            .body(response);
    }
}
