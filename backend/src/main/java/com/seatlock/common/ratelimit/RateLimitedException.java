package com.seatlock.common.ratelimit;

import com.seatlock.common.exception.SeatLockException;
import java.util.Map;

/** 429 {@code RATE_LIMITED}. {@code retryAfterSeconds} becomes the Retry-After header. */
public class RateLimitedException extends SeatLockException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("RATE_LIMITED", "Too many requests; please wait " + retryAfterSeconds + " s and try again");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("retryAfterSeconds", retryAfterSeconds);
    }
}
