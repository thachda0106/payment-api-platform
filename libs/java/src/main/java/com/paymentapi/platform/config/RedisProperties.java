package com.paymentapi.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Optional Redis configuration.
 * Only validated when {@code REDIS_URL} env var is set.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "platform.redis")
@ConditionalOnProperty(name = "platform.redis.url")
public class RedisProperties {

    @NotBlank
    private String url;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
