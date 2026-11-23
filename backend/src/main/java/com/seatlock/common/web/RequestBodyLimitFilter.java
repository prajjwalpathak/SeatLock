package com.seatlock.common.web;

import com.seatlock.common.exception.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Refuses bodies over {@code seatlock.http.max-body-size} (1 MB, same as Nginx's {@code client_max_body_size}) by
 * their {@code Content-Length}, before anything reads them: 413 {@code PAYLOAD_TOO_LARGE} as a ProblemDetail via the
 * MVC exception handler. Our largest real body is a few hundred bytes (webhooks, show creation).
 * <p>Chunked bodies have no Content-Length; in prod Nginx enforces the same limit on them (413 at the edge). Locally
 * they are only bounded by the per-field {@code @Size} limits once parsed (docs/SECURITY.md).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2) // after RequestIdFilter, so the 413 carries a requestId
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final HandlerExceptionResolver resolver;

    public RequestBodyLimitFilter(@Value("${seatlock.http.max-body-size:1MB}") DataSize maxBodySize,
                                  @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.maxBytes = maxBodySize.toBytes();
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            if (resolver.resolveException(request, response, null, new PayloadTooLargeException(maxBytes)) == null) {
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
