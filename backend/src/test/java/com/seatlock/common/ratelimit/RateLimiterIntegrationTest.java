package com.seatlock.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import testsupport.IntegrationTest;

/**
 * Bucket4j's Lettuce integration against real Redis with Boot's Lettuce 7. If the module were binary-incompatible
 * with Lettuce 7 this test would fail with NoSuchMethodError instead of passing (ADR-009).
 */
@IntegrationTest
class RateLimiterIntegrationTest {

    private static final RateLimitProperties.Limit THREE_PER_HOUR = new RateLimitProperties.Limit(3, 3, Duration.ofHours(1));

    @Autowired RateLimiter limiter;

    @Test
    void allowsTheBurstThenRejectsWithARetryAfter() {
        String key = "rl:test:" + UUID.randomUUID();

        assertThat(limiter.tryConsume(key, THREE_PER_HOUR).remaining()).isEqualTo(2);
        assertThat(limiter.tryConsume(key, THREE_PER_HOUR).remaining()).isEqualTo(1);
        assertThat(limiter.tryConsume(key, THREE_PER_HOUR).remaining()).isZero();

        RateLimiter.Decision fourth = limiter.tryConsume(key, THREE_PER_HOUR);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfterSeconds()).isBetween(1L, 1200L);            // ~20 min until 1 token refills
    }

    @Test
    void keysHaveIndependentBuckets() {
        String a = "rl:test:" + UUID.randomUUID();
        String b = "rl:test:" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(a, THREE_PER_HOUR);
        }

        assertThat(limiter.tryConsume(a, THREE_PER_HOUR).allowed()).isFalse();
        assertThat(limiter.tryConsume(b, THREE_PER_HOUR).allowed()).isTrue();
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        var fast = new RateLimitProperties.Limit(1, 1, Duration.ofMillis(300));
        String key = "rl:test:" + UUID.randomUUID();

        assertThat(limiter.tryConsume(key, fast).allowed()).isTrue();
        assertThat(limiter.tryConsume(key, fast).allowed()).isFalse();
        Thread.sleep(400);
        assertThat(limiter.tryConsume(key, fast).allowed()).isTrue();
    }
}
