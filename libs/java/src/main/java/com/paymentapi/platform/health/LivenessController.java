package com.paymentapi.platform.health;

import com.paymentapi.platform.telemetry.TelemetryMetadataProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Map;

/**
 * GET /liveness — lightweight probe.
 * Returns 200 as long as the process is alive (no I/O, no dependency checks).
 * Fails only if the JVM is completely broken (deadlock, OOM).
 */
@RestController
public class LivenessController {

    private final TelemetryMetadataProperties metadata;
    private final double startTime;

    public LivenessController(TelemetryMetadataProperties metadata) {
        this.metadata = metadata;
        this.startTime = ManagementFactory.getRuntimeMXBean().getStartTime() / 1000.0;
    }

    @GetMapping("/liveness")
    public ProbeResponse liveness() {
        double uptime = (System.currentTimeMillis() / 1000.0) - startTime;
        return ProbeResponse.ok(
            metadata.getServiceName(),
            metadata.getServiceVersion(),
            uptime,
            Collections.emptyMap()
        );
    }
}
