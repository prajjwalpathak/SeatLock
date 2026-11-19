package com.seatlock.booking.hold;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads live holds for many seats with a single {@code MGET} (one round trip, one command),
 * instead of N {@code GET}s or a {@code SCAN} over the keyspace.
 * Errors (Redis down, timeout) propagate as {@code DataAccessException}; the caller decides how to degrade.
 */
@Component
public class SeatHoldReader {

    private final StringRedisTemplate redis;

    public SeatHoldReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Set<Long> heldSeatIds(long showId, Collection<Long> seatIds) {
        if (seatIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = List.copyOf(seatIds);
        List<String> keys = new ArrayList<>(ids.size());
        for (Long seatId : ids) {
            keys.add(HoldKeys.seatKey(showId, seatId));
        }

        List<String> values = redis.opsForValue().multiGet(keys); // same order as keys; null = not held
        if (values == null) {
            return Set.of();
        }
        Set<Long> held = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            if (values.get(i) != null) {
                held.add(ids.get(i));
            }
        }
        return Set.copyOf(held);
    }
}
