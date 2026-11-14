package com.seatlock.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the SPA. Exact origins only (never "*"). Note for W2: Spring Security must call
 * http.cors(...) so these rules are applied before authentication.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final CorsProperties properties;

    public CorsConfig(CorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowedHeaders("Content-Type", "Authorization", "Idempotency-Key", "X-Request-Id")
                .exposedHeaders("X-Request-Id", "Location", "Retry-After") // readable by browser JS
                .allowCredentials(false) // JWT in Authorization header; revisit if W2 uses a cookie
                .maxAge(3600);           // cache preflight results for 1 hour
    }
}