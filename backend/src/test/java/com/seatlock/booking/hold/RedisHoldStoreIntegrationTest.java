package com.seatlock.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.booking.hold.HoldAttempt.Held;
import com.seatlock.booking.hold.HoldAttempt.HoldExists;
import com.seatlock.booking.hold.HoldAttempt.SeatsTaken;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import testsupport.IntegrationTest;

/** The Lua scripts against real Redis. Random show/user IDs per test: no shared keys, no cleanup. */
@IntegrationTest
class RedisHoldStoreIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(8);
    private static final Instant EXPIRES = Instant.parse("2026-12-01T14:08:00Z");

    @Autowired RedisHoldStore store;
    @Autowired StringRedisTemplate redis;

    private final long show = randomId();
    private final long user = randomId();

    @Test
    void holdsEverySeatWithTheTtlAndWritesTheRecord() {
        String holdId = newHoldId();

        assertThat(store.tryHold(holdId, user, show, List.of(1L, 2L, 3L), TTL, EXPIRES)).isEqualTo(new Held(holdId));

        for (long seat : List.of(1L, 2L, 3L)) {
            String key = HoldKeys.seatKey(show, seat);
            assertThat(redis.opsForValue().get(key)).isEqualTo(holdId);
            assertThat(redis.getExpire(key)).isBetween(TTL.toSeconds() - 5, TTL.toSeconds());
        }
        assertThat(redis.opsForValue().get(HoldKeys.userShowKey(user, show))).isEqualTo(holdId);
        assertThat(redis.getExpire(HoldKeys.recordKey(holdId))).isBetween(TTL.toSeconds() - 5, TTL.toSeconds());
        assertThat(store.find(holdId)).contains(new HoldRecord(holdId, user, show, List.of(1L, 2L, 3L), EXPIRES));
    }

    @Test
    void allOrNothingWhenAnySeatIsTaken() {
        long otherUser = user + 1;
        store.tryHold(newHoldId(), otherUser, show, List.of(2L, 5L), TTL, EXPIRES);
        String mine = newHoldId();

        HoldAttempt attempt = store.tryHold(mine, user, show, List.of(1L, 2L, 3L, 5L), TTL, EXPIRES);

        assertThat(attempt).isEqualTo(new SeatsTaken(List.of(2L, 5L)));          // reports exactly the taken seats
        assertThat(redis.hasKey(HoldKeys.seatKey(show, 1))).isFalse();            // ...and writes nothing
        assertThat(redis.hasKey(HoldKeys.seatKey(show, 3))).isFalse();
        assertThat(redis.hasKey(HoldKeys.userShowKey(user, show))).isFalse();
        assertThat(store.find(mine)).isEmpty();
    }

    @Test
    void oneActiveHoldPerUserPerShow() {
        String first = newHoldId();
        store.tryHold(first, user, show, List.of(1L), TTL, EXPIRES);

        assertThat(store.tryHold(newHoldId(), user, show, List.of(9L), TTL, EXPIRES)).isEqualTo(new HoldExists(first));
        assertThat(redis.hasKey(HoldKeys.seatKey(show, 9))).isFalse();

        // a different show is a separate hold
        String elsewhere = newHoldId();
        assertThat(store.tryHold(elsewhere, user, show + 1, List.of(1L), TTL, EXPIRES)).isEqualTo(new Held(elsewhere));
    }

    @Test
    void releaseFreesTheSeatsAndAllowsANewHold() {
        String holdId = newHoldId();
        store.tryHold(holdId, user, show, List.of(1L, 2L), TTL, EXPIRES);

        assertThat(store.release(store.find(holdId).orElseThrow())).isEqualTo(2);

        assertThat(redis.hasKey(HoldKeys.seatKey(show, 1))).isFalse();
        assertThat(redis.hasKey(HoldKeys.userShowKey(user, show))).isFalse();
        assertThat(store.find(holdId)).isEmpty();
        String again = newHoldId();
        assertThat(store.tryHold(again, user, show, List.of(1L, 2L), TTL, EXPIRES)).isEqualTo(new Held(again));
    }

    @Test
    void releaseNeverDeletesASeatThatNowBelongsToAnotherHold() {
        String mine = newHoldId();
        store.tryHold(mine, user, show, List.of(1L, 2L), TTL, EXPIRES);
        HoldRecord record = store.find(mine).orElseThrow();
        // Simulate: my seat-1 key expired and another user's newer hold took seat 1.
        redis.opsForValue().set(HoldKeys.seatKey(show, 1), "someone-elses-hold", TTL);

        assertThat(store.release(record)).isEqualTo(1);                           // only seat 2 was still mine

        assertThat(redis.opsForValue().get(HoldKeys.seatKey(show, 1))).isEqualTo("someone-elses-hold");
        assertThat(redis.hasKey(HoldKeys.seatKey(show, 2))).isFalse();
    }

    @Test
    void anExpiredHoldDisappearsCompletelyAndTheSeatsCanBeHeldAgain() throws InterruptedException {
        String shortHold = newHoldId();
        store.tryHold(shortHold, user, show, List.of(1L), Duration.ofMillis(100), EXPIRES);

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(HoldKeys.seatKey(show, 1))) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(store.find(shortHold)).isEmpty();
        String next = newHoldId();
        assertThat(store.tryHold(next, user + 1, show, List.of(1L), TTL, EXPIRES)).isEqualTo(new Held(next));
        assertThat(store.tryHold(newHoldId(), user, show, List.of(2L), TTL, EXPIRES)).isInstanceOf(Held.class); // user key expired too
    }

    @Test
    void anEmptySeatListIsAProgrammingError() {
        assertThatThrownBy(() -> store.tryHold(newHoldId(), user, show, List.of(), TTL, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String newHoldId() {
        return UUID.randomUUID().toString();
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }
}
