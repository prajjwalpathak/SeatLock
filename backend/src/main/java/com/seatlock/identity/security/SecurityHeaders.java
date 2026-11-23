package com.seatlock.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Response headers owned by the app (W8·D1), applied to both filter chains. Split with Nginx (docs/SECURITY.md):
 * <ul>
 *   <li><b>Nginx</b> owns {@code Strict-Transport-Security}: it terminates TLS and also serves Grafana and its own
 *       error pages. Spring's HSTS is off, so prod no longer sends the header twice.</li>
 *   <li><b>Spring</b> owns the content headers, so they are the same locally, in tests and in prod:
 *       {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY}, no-store cache headers (Spring
 *       defaults), {@code Referrer-Policy}, {@code Permissions-Policy}, and a {@code Content-Security-Policy} for the
 *       JSON API (the API never serves HTML or scripts). Swagger UI is outside /api/, so it keeps working.</li>
 * </ul>
 * Header writers don't overwrite a header the handler already set (e.g. {@code Referrer-Policy: no-referrer} on the
 * Google sign-in redirect).
 */
public final class SecurityHeaders {

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    private SecurityHeaders() {
    }

    public static void apply(HttpSecurity http) throws Exception {
        http.headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts.disable())   // Nginx sends it (one copy, whole host)
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        SecurityHeaders::isApi, new StaticHeadersWriter("Content-Security-Policy", API_CSP))));
    }

    private static boolean isApi(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }
}
