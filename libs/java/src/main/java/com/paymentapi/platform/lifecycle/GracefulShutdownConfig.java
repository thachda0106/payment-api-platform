package com.paymentapi.platform.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Handles graceful shutdown.
 * The OTel Java Agent manages its own lifecycle — this component only logs shutdown events.
 */
@Component
public class GracefulShutdownConfig {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownConfig.class);

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        log.info("Application shutting down gracefully. OTel Agent will flush spans automatically.");
    }
}
