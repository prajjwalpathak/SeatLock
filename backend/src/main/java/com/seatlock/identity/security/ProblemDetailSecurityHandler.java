package com.seatlock.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Security errors happen in the filter chain, before Spring MVC. This hands them to MVC's
 * exception resolvers, so GlobalExceptionHandler renders the same ProblemDetail
 * (code + requestId) as every other error.
 */
final class ProblemDetailSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    ProblemDetailSecurityHandler(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
        resolve(request, response, ex, HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        resolve(request, response, ex, HttpServletResponse.SC_FORBIDDEN);
    }

    private void resolve(HttpServletRequest request, HttpServletResponse response,
                         Exception ex, int fallbackStatus) throws IOException {
        if (resolver.resolveException(request, response, null, ex) == null) {
            response.sendError(fallbackStatus); // only if no MVC handler took it (shouldn't happen)
        }
    }
}
