package com.seatlock.common.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code seatlock.rate-limit.*}. Each policy has a token bucket per IP and per user:
 * {@code capacity} = burst size, {@code refillTokens} per {@code refillPeriod} = sustained rate.
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.rate-limit")
public record RateLimitProperties(boolean enabled, Map<String, @Valid Policy> policies) {

    public RateLimitProperties {
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }

    public record Policy(@NotNull @Valid Limit perIp, @NotNull @Valid Limit perUser) {}

    public record Limit(@Positive long capacity, @Positive long refillTokens, @NotNull Duration refillPeriod) {}
}
