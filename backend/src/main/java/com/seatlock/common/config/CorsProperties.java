package com.seatlock.common.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Browser origins allowed to call /api/**. Set per profile; in prod via CORS_ALLOWED_ORIGINS
 * (comma-separated). Empty = startup failure, so prod can't ship without it.
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {
}