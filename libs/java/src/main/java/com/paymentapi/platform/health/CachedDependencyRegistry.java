package com.paymentapi.platform.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Thread-safe registry of dependency health checks with TTL-based caching.
 *
 * <p>Readiness probes read from the cache (cheap, no I/O).
 * Cache entries expire after {@code ttl} seconds, triggering a fresh check.
 *
 * <p>Usage:
 * <pre>{@code
 * CachedDependencyRegistry registry = new CachedDependencyRegistry(5);
 * if (dataSource != null) {
 *     registry.register("database", () -> pingDb(dataSource));
 * }
 * }</pre>
 */
public class CachedDependencyRegistry {

    private static final Logger log = LoggerFactory.getLogger(CachedDependencyRegistry.class);

    private final Duration ttl;
    private final ConcurrentHashMap<String, CachedCheck> checks = new ConcurrentHashMap<>();

    public CachedDependencyRegistry(Duration ttl) {
        this.ttl = ttl;
    }

    public CachedDependencyRegistry(int ttlSeconds) {
        this(Duration.ofSeconds(ttlSeconds));
    }

    /**
     * Register a named dependency check.
     */
    public void register(String name, Supplier<Boolean> checkFn) {
        checks.put(name, new CachedCheck(checkFn));
    }

    /**
     * Return the current status of all registered checks.
     * Uses cached results within TTL; performs fresh checks on expiry.
     */
    public Map<String, CheckResult> getStatuses() {
        if (checks.isEmpty()) return Collections.emptyMap();

        Map<String, CheckResult> results = new ConcurrentHashMap<>();
        for (var entry : checks.entrySet()) {
            results.put(entry.getKey(), entry.getValue().getStatus());
        }
        return results;
    }

    /**
     * Force immediate recheck of all dependencies.
     */
    public void invalidate() {
        checks.values().forEach(c -> {
            c.lastResult = null;
            c.lastChecked = null;
        });
        log.debug("Dependency cache invalidated");
    }

    /** Internal wrapper with cached result. */
    private class CachedCheck {
        private final Supplier<Boolean> checkFn;
        private volatile CheckResult lastResult;
        private volatile Instant lastChecked;

        CachedCheck(Supplier<Boolean> checkFn) {
            this.checkFn = checkFn;
        }

        CheckResult getStatus() {
            // Return cached result if still fresh
            if (lastResult != null && lastChecked != null &&
                Duration.between(lastChecked, Instant.now()).compareTo(ttl) < 0) {
                return lastResult;
            }

            // Perform fresh check
            long start = System.nanoTime();
            boolean healthy;
            try {
                healthy = checkFn.get();
            } catch (Exception e) {
                log.warn("Dependency check failed: {}", e.getMessage());
                healthy = false;
            }
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;

            lastResult = healthy ? CheckResult.ok(latencyMs) : CheckResult.down(latencyMs);
            lastChecked = Instant.now();
            return lastResult;
        }
    }
}
