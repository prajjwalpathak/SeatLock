package com.seatlock.realtime;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-show event counter in Redis ({@code INCR} is atomic), so every instance numbers events from the
 * same sequence once the Pub/Sub relay (W5·D2) fans them out. No TTL: one small key per show.
 * Redis runs without persistence in prod, so a restart starts again at 1; clients treat a smaller
 * version than the last one seen as "resync", like a gap.
 */
@Component
class SeatEventVersions {

    static String key(long showId) {
        return "realtime:seat-version:show:" + showId;
    }

    private final StringRedisTemplate redis;

    SeatEventVersions(StringRedisTemplate redis) {
        this.redis = redis;
    }

    long next(long showId) {
        Long v = redis.opsForValue().increment(key(showId));
        if (v == null) {
            throw new IllegalStateException("INCR returned no value"); // only inside a pipeline/transaction
        }
        return v;
    }
}
