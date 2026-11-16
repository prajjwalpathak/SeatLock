package com.seatlock.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Token buckets in Redis via Bucket4j (compare-and-swap, so concurrent requests on any instance share one
 * bucket per key). <b>Fails open</b>: if Redis can't be reached the request is allowed. Rate limiting is
 * protection, not correctness; the protected operation (holds) answers 503 by itself when Redis is down.
 */
public class RateLimiter implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Outcome of one attempt. {@code remaining} is -1 when the limiter couldn't be consulted (fail-open). */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
        static Decision failOpen() {
            return new Decision(true, -1, 0);
        }
    }

    private final Supplier<ProxyManager<byte[]>> factory;
    private volatile ProxyManager<byte[]> buckets;
    private volatile boolean running;

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

    // Lifecycle: when the context stops (shutdown, or a paused test context), LettuceConnectionFactory shuts its
    // Lettuce client down. Drop the proxy built on it; the next call rebuilds it on the factory's new client.
    // (Without this, a cached proxy kept failing with "Connection is closed" after a restart.)

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        buckets = null;
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
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
