package com.seatlock.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Fail-open: when the bucket store can't be reached, requests are allowed (and nothing throws). */
class RateLimiterTest {

    private static final RateLimitProperties.Limit LIMIT = new RateLimitProperties.Limit(1, 1, Duration.ofMinutes(1));

    @Test
    void allowsTheRequestWhenRedisIsUnavailable() {
        RateLimiter limiter = new RateLimiter(() -> {
            throw new IllegalStateException("Redis client not available");
        });

        RateLimiter.Decision decision = limiter.tryConsume("rl:test:x", LIMIT);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(-1);
    }
}
