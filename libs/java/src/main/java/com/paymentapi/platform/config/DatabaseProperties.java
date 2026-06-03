package com.paymentapi.platform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Optional database configuration.
 * Only validated when {@code DATABASE_URL} env var (mapped to {@code platform.database.url}) is set.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "platform.database")
@ConditionalOnProperty(name = "platform.database.url")
public class DatabaseProperties {

    @NotBlank
    private String url;

    @Min(1)
    private int maxPoolSize = 10;

    @Min(0)
    private int minIdle = 2;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    public int getMinIdle() { return minIdle; }
    public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
}
