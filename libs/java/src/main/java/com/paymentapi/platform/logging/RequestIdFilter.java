package com.paymentapi.platform.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Extracts or generates a {@code requestId} for every HTTP request.
 * <ul>
 *   <li>If {@code X-Request-Id} header is present → reuse it (allows client correlation)</li>
 *   <li>If absent → generate UUID v4</li>
 *   <li>Injects {@code requestId} into SLF4J MDC for structured log output</li>
 *   <li>Returns {@code requestId} in the {@code X-Request-Id} response header</li>
 * </ul>
 *
 * <p><b>Note:</b> {@code traceId} and {@code spanId} are automatically injected
 * into MDC by the OTel Java Agent — this filter handles only {@code requestId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        request.setAttribute(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_KEY, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
