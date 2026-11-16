package com.seatlock.booking.hold;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis side of seat holds: the two Lua scripts plus reading the hold record.
 * Business rules (seat limit, validation against the DB, who may release) live in {@code HoldService}.
 * Spring runs scripts with EVALSHA and falls back to EVAL when Redis doesn't have them cached yet.
 */
@Component
public class RedisHoldStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> HOLD_SEATS =
            RedisScript.of(new ClassPathResource("redis/hold_seats.lua"), List.class);
    private static final RedisScript<Long> RELEASE_SEATS =
            RedisScript.of(new ClassPathResource("redis/release_seats.lua"), Long.class);

    private final StringRedisTemplate redis;

    public RedisHoldStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** All or nothing: either every seat is held under {@code holdId}, or nothing changes. */
    public HoldAttempt tryHold(String holdId, long userId, long showId, List<Long> seatIds,
                               Duration ttl, Instant expiresAt) {
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("seatIds must not be empty");
        }
        List<String> keys = keysFor(showId, seatIds, userId, holdId);
        String csv = seatIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        @SuppressWarnings("unchecked")
        List<Object> result = redis.execute(HOLD_SEATS, keys,
                holdId, String.valueOf(ttl.toMillis()), String.valueOf(userId), String.valueOf(showId),
                csv, String.valueOf(expiresAt.toEpochMilli()));
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("hold_seats.lua returned no result");
        }

        return switch (String.valueOf(result.getFirst())) {
            case "OK" -> new HoldAttempt.Held(holdId);
            case "HOLD_EXISTS" -> new HoldAttempt.HoldExists(String.valueOf(result.get(1)));
            case "SEATS_TAKEN" -> new HoldAttempt.SeatsTaken(result.subList(1, result.size()).stream()
                    .map(pos -> seatIds.get(Integer.parseInt(String.valueOf(pos)) - 1))
                    .toList());
            default -> throw new IllegalStateException("Unexpected hold_seats.lua result: " + result);
        };
    }

    /** The live hold, or empty if it never existed or has expired. */
    public Optional<HoldRecord> find(String holdId) {
        Map<Object, Object> fields = redis.opsForHash().entries(HoldKeys.recordKey(holdId));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        List<Long> seatIds = Arrays.stream(String.valueOf(fields.get("seatIds")).split(","))
                .map(Long::valueOf)
                .toList();
        return Optional.of(new HoldRecord(
                holdId,
                Long.parseLong(String.valueOf(fields.get("userId"))),
                Long.parseLong(String.valueOf(fields.get("showId"))),
                seatIds,
                Instant.ofEpochMilli(Long.parseLong(String.valueOf(fields.get("expiresAt"))))));
    }

    /** Deletes the hold's keys that still belong to it; returns how many seats were released. */
    public int release(HoldRecord hold) {
        List<String> keys = keysFor(hold.showId(), hold.seatIds(), hold.userId(), hold.holdId());
        Long released = redis.execute(RELEASE_SEATS, keys, hold.holdId());
        return released == null ? 0 : released.intValue();
    }

    private static List<String> keysFor(long showId, List<Long> seatIds, long userId, String holdId) {
        List<String> keys = new ArrayList<>(seatIds.size() + 2);
        for (Long seatId : seatIds) {
            keys.add(HoldKeys.seatKey(showId, seatId));
        }
        keys.add(HoldKeys.userShowKey(userId, showId));
        keys.add(HoldKeys.recordKey(holdId));
        return keys;
    }
}
