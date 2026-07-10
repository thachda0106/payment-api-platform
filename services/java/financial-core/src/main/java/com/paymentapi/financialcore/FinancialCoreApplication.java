package com.paymentapi.financialcore;

import com.paymentapi.platform.health.CachedDependencyRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Financial Core — Ledger + Wallet bounded context.
 * Uses platform-libs for health probes, structured logging, and config.
 * OTel Java Agent handles auto-instrumentation (HTTP, JPA, Kafka).
 */
@SpringBootApplication(scanBasePackages = "com.paymentapi")
@EnableScheduling  // For LedgerOutboxPoller
public class FinancialCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinancialCoreApplication.class, args);
    }

    /**
     * Register database + Kafka health checks with the platform's cached dependency registry.
     */
    @Bean
    public Object registerHealthChecks(CachedDependencyRegistry registry,
                                       DataSource dataSource,
                                       KafkaListenerEndpointRegistry kafkaListenerRegistry) {
        registry.register("database", () -> {
            try (Connection conn = dataSource.getConnection()) {
                return conn.isValid(3);
            } catch (Exception e) {
                return false;
            }
        });
        // Honest Kafka health: the @KafkaListener container must actually be running,
        // not merely that the Spring context started.
        registry.register("kafka", () -> {
            var containers = kafkaListenerRegistry.getListenerContainers();
            return !containers.isEmpty()
                && containers.stream().allMatch(MessageListenerContainer::isRunning);
        });
        return new Object();
    }
}
