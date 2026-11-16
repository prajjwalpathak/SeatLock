package com.seatlock.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token buckets in Redis via Bucket4j (compare-and-swap, so concurrent requests on any instance share one
 * bucket per key). <b>Fails open</b>: if Redis can't be reached the request is allowed. Rate limiting is
 * protection, not correctness; the protected operation (holds) answers 503 by itself when Redis is down.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Outcome of one attempt. {@code remaining} is -1 when the limiter couldn't be consulted (fail-open). */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
        static Decision failOpen() {
            return new Decision(true, -1, 0);
        }
    }

    private final Supplier<ProxyManager<byte[]>> factory;
    private volatile ProxyManager<byte[]> buckets;

    /** {@code factory} is called once, on first use (thread-safe). */
    public RateLimiter(Supplier<ProxyManager<byte[]>> factory) {
        this.factory = factory;
    }

    private ProxyManager<byte[]> buckets() {
        ProxyManager<byte[]> b = buckets;
        if (b == null) {
            synchronized (this) {
                b = buckets;
                if (b == null) {
                    buckets = b = factory.get();
                }
            }
        }
        return b;
    }

    public Decision tryConsume(String key, RateLimitProperties.Limit limit) {
        try {
            ConsumptionProbe probe = buckets().builder()
                    .build(key.getBytes(StandardCharsets.UTF_8), () -> configuration(limit))
                    .tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return new Decision(true, probe.getRemainingTokens(), 0);
            }
            long seconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L); // round up
            return new Decision(false, 0, seconds);
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable for {}; allowing the request ({})", key, e.toString());
            return Decision.failOpen();
        }
    }

    static BucketConfiguration configuration(RateLimitProperties.Limit limit) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.refillTokens(), limit.refillPeriod())
                        .build())
                .build();
    }
}
