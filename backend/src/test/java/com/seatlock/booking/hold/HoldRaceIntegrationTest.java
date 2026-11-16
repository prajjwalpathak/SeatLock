package com.seatlock.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.common.exception.SeatLockException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import testsupport.IntegrationTest;

/**
 * Races through the real HoldService (DB checks + Lua) with real threads released at the same instant.
 * Smoke version (20 threads, repeated); the 200-thread HTTP test with metrics comes on W3·D6.
 * <p>Each outcome is either a {@link Hold} or the error code, so the assertions see every result
 * and an unexpected exception (e.g. a 500-type error) fails the test instead of being ignored.</p>
 */
@IntegrationTest
class HoldRaceIntegrationTest {

    private static final int THREADS = 20;

    @Autowired HoldService holds;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    private long showId;
    private List<Long> seats;

    @BeforeEach
    void pickUpcomingShow() {
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id LIMIT 1
                """, Long.class);
        seats = jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT ?
                """, Long.class, showId, THREADS + 1);
    }

    @AfterEach
    void removeHoldKeys() {
        Set<String> keys = redis.keys("hold:*"); // test-only
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @RepeatedTest(5)
    void twentyUsersOneSeatExactlyOneWinner() throws Exception {
        long seat = seats.getFirst();

        List<Object> outcomes = race(i -> () -> holds.createHold(1_000 + i, showId, List.of(seat)));

        List<Hold> winners = winners(outcomes);
        assertThat(winners).hasSize(1);
        assertThat(codes(outcomes)).hasSize(THREADS - 1).containsOnly("SEATS_UNAVAILABLE");
        assertThat(redis.opsForValue().get(HoldKeys.seatKey(showId, seat))).isEqualTo(winners.getFirst().holdId());
    }

    /**
     * User i wants seats [i, i+1]: every request overlaps its neighbours. All-or-nothing means no two
     * winners share a seat, and every seat key points at the hold that contains it (no half-written holds).
     */
    @RepeatedTest(5)
    void overlappingMultiSeatHoldsNeverShareASeat() throws Exception {
        List<Object> outcomes = race(i -> () ->
                holds.createHold(2_000 + i, showId, List.of(seats.get(i), seats.get(i + 1))));

        List<Hold> winners = winners(outcomes);
        assertThat(winners).isNotEmpty();
        assertThat(codes(outcomes)).containsOnly("SEATS_UNAVAILABLE");

        Set<Long> claimed = new HashSet<>();
        for (Hold hold : winners) {
            for (Hold.HeldSeat seat : hold.seats()) {
                assertThat(claimed.add(seat.seatId())).as("seat %d held twice", seat.seatId()).isTrue();
                assertThat(redis.opsForValue().get(HoldKeys.seatKey(showId, seat.seatId()))).isEqualTo(hold.holdId());
            }
        }
        // Seats no winner claimed must have no key at all (losers wrote nothing).
        for (Long seat : seats) {
            if (!claimed.contains(seat)) {
                assertThat(redis.hasKey(HoldKeys.seatKey(showId, seat))).as("seat %d", seat).isFalse();
            }
        }
    }

    /** Double-click / many tabs: one user racing for different seats still gets exactly one hold. */
    @RepeatedTest(5)
    void oneUserRacingGetsExactlyOneHold() throws Exception {
        List<Object> outcomes = race(i -> () -> holds.createHold(3_000, showId, List.of(seats.get(i))));

        assertThat(winners(outcomes)).hasSize(1);
        assertThat(codes(outcomes)).hasSize(THREADS - 1).containsOnly("HOLD_EXISTS");
    }

    // ------------------------------------------------------------------ race harness

    /** Starts THREADS tasks, releases them together, returns each Hold or error code (never swallows others). */
    private List<Object> race(IntFunction<Callable<Hold>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS); // real platform threads, all waiting at once
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<Hold> call = task.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return call.call();
                    } catch (SeatLockException e) {
                        return e.getCode();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS)); // any other exception fails the test here
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Hold> winners(List<Object> outcomes) {
        return outcomes.stream().filter(Hold.class::isInstance).map(Hold.class::cast).toList();
    }

    private static List<String> codes(List<Object> outcomes) {
        return outcomes.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
