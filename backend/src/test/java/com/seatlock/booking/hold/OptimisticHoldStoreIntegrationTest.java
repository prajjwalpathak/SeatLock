package com.seatlock.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import testsupport.IntegrationTest;

/** The OPTIMISTIC store against real Postgres (built by hand: the app context uses the default strategy). */
@IntegrationTest
class OptimisticHoldStoreIntegrationTest {

    static final Duration TTL = Duration.ofMinutes(8);

    @Autowired JdbcClient jdbcClient;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository users;

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    OptimisticHoldStore store;
    long showId;
    List<Long> seats;
    long alice;
    long bob;

    @BeforeEach
    void setUp() {
        store = new OptimisticHoldStore(jdbcClient, tx, clock);
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id LIMIT 1""", Long.class);
        seats = jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 4""",
                Long.class, showId);
        alice = user("alice");
        bob = user("bob");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM seat_holds");
    }

    @Test
    void holdsAllSeatsAndFindsTheHold() {
        String id = newId();
        assertThat(store.tryHold(id, alice, showId, seats.subList(0, 2), TTL, expires()))
                .isEqualTo(new HoldAttempt.Held(id));

        HoldRecord record = store.find(id).orElseThrow();
        assertThat(record.userId()).isEqualTo(alice);
        assertThat(record.seatIds()).containsExactlyElementsOf(seats.subList(0, 2));
        assertThat(store.heldSeatIds(showId, seats)).containsExactlyInAnyOrderElementsOf(seats.subList(0, 2));
    }

    @Test
    void overlappingHoldIsAllOrNothing() {
        store.tryHold(newId(), alice, showId, List.of(seats.get(1)), TTL, expires());

        HoldAttempt attempt = store.tryHold(newId(), bob, showId, seats.subList(0, 3), TTL, expires());

        assertThat(attempt).isEqualTo(new HoldAttempt.SeatsTaken(List.of(seats.get(1))));
        assertThat(store.heldSeatIds(showId, seats)).containsExactly(seats.get(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_holds WHERE user_id = ?", Integer.class, bob))
                .as("nothing written for the loser").isZero();
    }

    @Test
    void oneLiveHoldPerUserAndShow() {
        String first = newId();
        store.tryHold(first, alice, showId, List.of(seats.get(0)), TTL, expires());

        assertThat(store.tryHold(newId(), alice, showId, List.of(seats.get(1)), TTL, expires()))
                .isEqualTo(new HoldAttempt.HoldExists(first));
    }

    @Test
    void anExpiredHoldFreesItsSeatsForTheNextUser() {
        String old = newId();
        store.tryHold(old, alice, showId, List.of(seats.get(0)), TTL, expires());
        now.set(now.get().plus(TTL).plusSeconds(1));

        assertThat(store.find(old)).isEmpty();
        assertThat(store.heldSeatIds(showId, seats)).isEmpty();
        String fresh = newId();
        assertThat(store.tryHold(fresh, bob, showId, List.of(seats.get(0)), TTL, expires()))
                .isEqualTo(new HoldAttempt.Held(fresh));
        assertThat(jdbc.queryForObject("SELECT version FROM seat_hold_claims WHERE show_id = ? AND seat_id = ?",
                Long.class, showId, seats.get(0))).as("the claim row changed hands once").isEqualTo(1);
    }

    @Test
    void releaseFreesOnlyThisHoldsSeats() {
        String id = newId();
        store.tryHold(id, alice, showId, seats.subList(0, 2), TTL, expires());

        assertThat(store.release(store.find(id).orElseThrow())).isEqualTo(2);
        assertThat(store.find(id)).isEmpty();
        assertThat(store.heldSeatIds(showId, seats)).isEmpty();
    }

    @Test
    void twentyConcurrentClaimsOfOneSeatHaveOneWinner() throws Exception {
        List<Long> racers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            racers.add(user("racer" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HoldAttempt>> attempts = new ArrayList<>();
        for (long user : racers) {
            attempts.add(pool.submit(() -> {
                go.await();
                return store.tryHold(newId(), user, showId, List.of(seats.get(3)), TTL, expires());
            }));
        }
        go.countDown();
        int held = 0;
        for (Future<HoldAttempt> f : attempts) {
            if (f.get() instanceof HoldAttempt.Held) {
                held++;
            } else {
                assertThat(f.get()).isEqualTo(new HoldAttempt.SeatsTaken(List.of(seats.get(3))));
            }
        }
        pool.shutdown();
        assertThat(held).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_holds", Integer.class)).isEqualTo(1);
    }

    private Instant expires() {
        return now.get().plus(TTL);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private long user(String name) {
        return users.save(new User(name + "-" + UUID.randomUUID() + "@example.com", "{bcrypt}unused", name,
                Role.USER, AuthProvider.LOCAL)).getId();
    }
}
