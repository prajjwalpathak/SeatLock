package com.seatlock.payment.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code seatlock.refunds.*}: the worker (batch size, lease while a provider call is in flight) and the
 * cancellation policy (tiers: at least {@code hoursBefore} hours before the show → {@code percent} back).
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.refunds")
public record RefundProperties(@Min(1) @Max(500) int batchSize,
                               @NotNull Duration lease,
                               @NotEmpty List<@Valid Tier> policy) {

    public record Tier(@Min(0) int hoursBefore, @Min(0) @Max(100) int percent) {}
}
