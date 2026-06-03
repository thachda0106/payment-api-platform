package com.paymentapi.platform.health;

import com.paymentapi.platform.telemetry.TelemetryMetadataProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GET /startup — probe that gates traffic until initialization is complete.
 * Returns 503 until the first successful readiness check, then 200 permanently.
 */
@RestController
public class StartupController {

    private final TelemetryMetadataProperties metadata;
    private final CachedDependencyRegistry registry;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final double startTime;

    public StartupController(TelemetryMetadataProperties metadata,
                              CachedDependencyRegistry registry) {
        this.metadata = metadata;
        this.registry = registry;
        this.startTime = ManagementFactory.getRuntimeMXBean().getStartTime() / 1000.0;
    }

    @GetMapping("/startup")
    public ResponseEntity<ProbeResponse> startup() {
        double uptime = (System.currentTimeMillis() / 1000.0) - startTime;
        Map<String, CheckResult> statuses = registry.getStatuses();

        // Once all deps are healthy, latch to true permanently
        boolean allHealthy = statuses.isEmpty() ||
            statuses.values().stream().allMatch(CheckResult::isHealthy);
        if (allHealthy) {
            started.set(true);
        }

        ProbeResponse response = started.get()
            ? ProbeResponse.ok(metadata.getServiceName(), metadata.getServiceVersion(), uptime, statuses)
            : ProbeResponse.notReady(metadata.getServiceName(), metadata.getServiceVersion(), uptime, statuses);

        return ResponseEntity
            .status(started.get() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
            .body(response);
    }
}
