package com.seatlock.common.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Browser origins allowed to call /api/**. Set per profile; in prod via CORS_ALLOWED_ORIGINS
 * (comma-separated). Empty = startup failure, so prod can't ship without it.
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {

    /** scheme://host[:port], nothing else: no "*", no path, no trailing slash (browsers send exactly this). */
    private static final Pattern ORIGIN = Pattern.compile("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?");

    public CorsProperties {
        if (allowedOrigins != null) {
            allowedOrigins = allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
            for (String origin : allowedOrigins) {
                if (!ORIGIN.matcher(origin).matches()) {
                    throw new IllegalArgumentException("seatlock.cors.allowed-origins: '" + origin
                            + "' is not an exact origin (scheme://host[:port], no '*', path or trailing slash)");
                }
            }
        }
    }
}