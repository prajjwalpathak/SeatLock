package com.seatlock.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the SPA lives ({@code seatlock.frontend.base-url}), e.g. for redirects after Google sign-in.
 * Fixed per environment and never taken from the request, so it can't become an open redirect.
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.frontend")
public record FrontendProperties(
        @NotBlank @Pattern(regexp = "https?://[^/?#]+", message = "must be scheme://host[:port] without a path")
        String baseUrl) {
}
