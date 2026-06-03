package com.paymentapi.financialcore;

import com.paymentapi.platform.health.CachedDependencyRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Financial Core — Ledger + Wallet bounded context.
 * Uses platform-libs for health probes, structured logging, and config.
 * OTel Java Agent handles auto-instrumentation (HTTP, JPA, Kafka).
 */
@SpringBootApplication(scanBasePackages = "com.paymentapi")
public class FinancialCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinancialCoreApplication.class, args);
    }

    /**
     * Register database health check with the platform's cached dependency registry.
     */
    @Bean
    public Object registerHealthChecks(CachedDependencyRegistry registry, DataSource dataSource) {
        registry.register("database", () -> {
            try (Connection conn = dataSource.getConnection()) {
                return conn.isValid(3);
            } catch (Exception e) {
                return false;
            }
        });
        return new Object();
    }
}
