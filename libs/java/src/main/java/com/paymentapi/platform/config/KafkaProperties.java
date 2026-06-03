package com.paymentapi.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Optional Kafka configuration.
 * Only validated when {@code KAFKA_BOOTSTRAP_SERVERS} env var is set.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "platform.kafka")
@ConditionalOnProperty(name = "platform.kafka.bootstrap-servers")
public class KafkaProperties {

    @NotBlank
    private String bootstrapServers;

    @NotBlank
    private String consumerGroup = "default";

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
}
