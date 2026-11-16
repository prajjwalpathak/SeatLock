package com.seatlock.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import testsupport.IntegrationTest;

/**
 * SeatHoldReader against real Redis. Each test uses its own random show ID, so tests never see
 * each other's keys and need no cleanup beyond the TTLs.
 */
@IntegrationTest
class SeatHoldReaderIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(1);

    @Autowired SeatHoldReader reader;
    @Autowired StringRedisTemplate redis;

    @Test
    void returnsOnlySeatsWithALiveHoldForThatShow() {
        long show = randomShowId();
        long otherShow = show + 1;
        hold(show, 2, "hold-a");
        hold(show, 5, "hold-a");
        hold(otherShow, 3, "hold-b"); // same seat range, different show

        assertThat(reader.heldSeatIds(show, List.of(1L, 2L, 3L, 4L, 5L))).containsExactlyInAnyOrder(2L, 5L);
        assertThat(reader.heldSeatIds(otherShow, List.of(1L, 2L, 3L, 5L))).containsExactly(3L);
    }

    @Test
    void anExpiredHoldIsNoLongerHeld() throws InterruptedException {
        long show = randomShowId();
        redis.opsForValue().set(HoldKeys.seatKey(show, 1), "hold-x", Duration.ofMillis(50));
        hold(show, 2, "hold-y");

        waitUntilGone(HoldKeys.seatKey(show, 1));

        assertThat(reader.heldSeatIds(show, List.of(1L, 2L))).containsExactly(2L);
    }

    @Test
    void emptyInputMakesNoRedisCall() {
        long before = mgetCalls();

        assertThat(reader.heldSeatIds(randomShowId(), List.of())).isEmpty();

        assertThat(mgetCalls()).isEqualTo(before);
    }

    @Test
    void aWholeVenueIsReadInExactlyOneMget() {
        long show = randomShowId();
        List<Long> seats = LongStream.rangeClosed(1, 300).boxed().toList();
        hold(show, 10, "h");
        hold(show, 300, "h");
        long before = mgetCalls();

        assertThat(reader.heldSeatIds(show, seats)).containsExactlyInAnyOrder(10L, 300L);

        assertThat(mgetCalls()).isEqualTo(before + 1); // 300 seats, one command, one round trip
    }

    // ------------------------------------------------------------------ helpers

    private void hold(long showId, long seatId, String holdId) {
        redis.opsForValue().set(HoldKeys.seatKey(showId, seatId), holdId, TTL);
    }

    private static long randomShowId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }

    private void waitUntilGone(String key) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(redis.hasKey(key)).as("key should have expired").isFalse();
    }

    /** Server-side counter of MGET commands (INFO commandstats → cmdstat_mget:calls=N,...). */
    private long mgetCalls() {
        Properties stats = redis.execute((RedisCallback<Properties>) c -> c.serverCommands().info("commandstats"));
        String line = stats == null ? null : stats.getProperty("cmdstat_mget");
        if (line == null) {
            return 0;
        }
        String calls = line.split(",")[0];          // "calls=3"
        return Long.parseLong(calls.substring(calls.indexOf('=') + 1));
    }
}
