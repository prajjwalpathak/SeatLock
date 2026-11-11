package com.seatlock.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every HTTP request a request ID, returns it in the {@code X-Request-Id} response header
 * and stores it in the logging MDC so all log lines of one request can be correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    /** Client-supplied IDs are trusted only if short and safe (prevents log injection). */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // Lightweight access log at DEBUG (local only); skip noisy health checks.
            // getRequestURI() excludes the query string, so tokens in query params are never logged.
            if (log.isDebugEnabled() && !request.getRequestURI().startsWith("/actuator")) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                log.debug("{} {} -> {} ({} ms)",
                        request.getMethod(), request.getRequestURI(), response.getStatus(), ms);
            }
            MDC.remove(MDC_KEY);
        }
    }

    static String resolveRequestId(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}