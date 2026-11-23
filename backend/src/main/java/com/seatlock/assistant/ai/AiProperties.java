package com.seatlock.assistant.ai;

import com.seatlock.common.ratelimit.RateLimitProperties.Limit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code seatlock.ai.*}: model id, input limit, the AI thread pool and the quotas (ADR-016). */
@Validated
@ConfigurationProperties(prefix = "seatlock.ai")
public record AiProperties(
        @NotBlank String model,
        @Positive int maxInputChars,
        @Positive int executorThreads,
        @PositiveOrZero int executorQueue,
        @NotNull Duration auditRetention,
        @NotNull @Valid Quota quota) {

    /** Per-user daily (→ 429) and global per-minute / daily caps (→ fallback), all Bucket4j buckets in Redis. */
    public record Quota(@NotNull @Valid Limit userDaily,
                        @NotNull @Valid Limit globalPerMinute,
                        @NotNull @Valid Limit globalDaily) {}
}
