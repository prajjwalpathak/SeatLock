package com.seatlock.realtime;

import com.seatlock.catalog.application.SeatEventVersionLookup;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Reads the per-show counter that {@code publish_seat_event.lua} increments. */
@Component
class SeatEventVersionReader implements SeatEventVersionLookup {

    private final StringRedisTemplate redis;

    SeatEventVersionReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long currentVersion(long showId) {
        String value = redis.opsForValue().get(SeatEventVersions.key(showId));
        return value == null ? 0 : Long.parseLong(value);
    }
}
