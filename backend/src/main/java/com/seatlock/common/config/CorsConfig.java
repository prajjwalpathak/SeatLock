package com.seatlock.common.config;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS for the SPA. Exact origins only (never "*"). Exposed as a CorsConfigurationSource so
 * Spring Security applies it in its filter chain, before authentication (a preflight carries
 * no token and must not get a 401).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(properties.allowedOrigins());
        api.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        api.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key", "X-Request-Id"));
        api.setExposedHeaders(List.of("X-Request-Id", "Location", "Retry-After")); // readable by browser JS
        api.setAllowCredentials(false); // JWT in Authorization header; revisit if W2·D2 uses a cookie
        api.setMaxAge(3600L);           // cache preflight results for 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", api);
        return source;
    }
}
