package com.seatlock.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import testsupport.IntegrationTest;

/**
 * The sweeper's SQL against real Postgres: SKIP LOCKED batches, state machine, seats freed.
 * Bookings are inserted directly (HELD seats in show_seats + active booking_seats), on the LAST bookable
 * show so other test classes (which use the first) aren't affected; everything is cleaned up afterwards.
 */
@IntegrationTest
class BookingExpiryIntegrationTest {

    @Autowired BookingExpiryService expiry;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository users;

    private long userId;
    private long showId;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        userId = users.save(new User("sweep-" + UUID.randomUUID() + "@example.com", "{bcrypt}unused", "Sweep",
                Role.USER, AuthProvider.LOCAL)).getId();
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id DESC LIMIT 1
                """, Long.class);
        seats = jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 40
                """, Long.class, showId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM booking_seats WHERE booking_id IN (SELECT id FROM bookings WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM bookings WHERE user_id = ?", userId);
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ANY (?)",
                showId, seats.toArray(Long[]::new));
    }

    @Test
    void expiresOnlyUnpaidBookingsWhoseTimeIsUpAndFreesTheirSeats() {
        long heldExpired = booking("HELD", seats.get(0), "-1 minute");
        long pendingExpired = booking("PAYMENT_PENDING", seats.get(1), "-1 minute");
        long heldFuture = booking("HELD", seats.get(2), "+5 minutes");
        long confirmedPast = booking("CONFIRMED", seats.get(3), "-1 minute");   // paid: never expires

        int expired = expiry.expireBatch(Instant.now(), 100);

        assertThat(expired).isEqualTo(2);
        assertThat(status(heldExpired)).isEqualTo("EXPIRED");
        assertThat(status(pendingExpired)).isEqualTo("EXPIRED");
        assertThat(status(heldFuture)).isEqualTo("HELD");
        assertThat(status(confirmedPast)).isEqualTo("CONFIRMED");
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(seats.get(1))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(seats.get(2))).isEqualTo("HELD");
        assertThat(activeSeats(heldExpired)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM bookings WHERE id = ?", Long.class, heldExpired))
                .isEqualTo(1L);                                                     // optimistic lock bumped
    }

    @Test
    void anExpiredSeatCanBeSoldAgain() {
        long old = booking("HELD", seats.get(0), "-1 minute");
        expiry.expireBatch(Instant.now(), 100);

        // the partial unique index now allows a new live claim on the same seat
        long again = booking("HELD", seats.get(0), "+5 minutes");

        assertThat(activeSeats(old)).isZero();
        assertThat(activeSeats(again)).isEqualTo(1);
    }

    @Test
    void twoSweepersAtOnceExpireEveryBookingExactlyOnce() throws Exception {
        for (int i = 0; i < 30; i++) {
            booking("HELD", seats.get(i), "-1 minute");
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> sweepers = new ArrayList<>();
            for (int s = 0; s < 2; s++) {
                sweepers.add(pool.submit(() -> {
                    go.await();
                    int total = 0;
                    int n;
                    do {
                        n = expiry.expireBatch(Instant.now(), 5);          // small batches: many interleavings
                        total += n;
                    } while (n == 5);
                    return total;
                }));
            }
            go.countDown();
            int a = sweepers.get(0).get(30, TimeUnit.SECONDS);
            int b = sweepers.get(1).get(30, TimeUnit.SECONDS);

            assertThat(a + b).isEqualTo(30);                                        // no booking counted twice
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE user_id = ? AND status = 'EXPIRED'",
                    Integer.class, userId)).isEqualTo(30);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM show_seats WHERE show_id = ? AND seat_id = ANY (?) AND status = 'HELD'",
                    Integer.class, showId, seats.subList(0, 30).toArray(Long[]::new))).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    /** A row locked elsewhere (e.g. a payment confirming it) is skipped immediately, not waited on. */
    @Test
    void aLockedBookingIsSkippedAndPickedUpOnTheNextRun() throws Exception {
        long locked = booking("HELD", seats.get(0), "-1 minute");
        long free = booking("HELD", seats.get(1), "-1 minute");
        CountDownLatch lockTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT id FROM bookings WHERE id = ? FOR UPDATE", Long.class, locked);
                lockTaken.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(lockTaken.await(10, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            int first = expiry.expireBatch(Instant.now(), 100);
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertThat(first).isEqualTo(1);
            assertThat(status(free)).isEqualTo("EXPIRED");
            assertThat(status(locked)).isEqualTo("HELD");
            assertThat(millis).as("must not wait for the lock").isLessThan(5_000);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(expiry.expireBatch(Instant.now(), 100)).isEqualTo(1);
            assertThat(status(locked)).isEqualTo("EXPIRED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A booking with one seat; show_seats is HELD (or BOOKED when confirmed) like the real flow leaves it. */
    private long booking(String status, long seatId, String expiresIn) {
        Long id = jdbc.queryForObject("""
                INSERT INTO bookings (user_id, show_id, hold_id, status, total_paise, expires_at, confirmed_at)
                VALUES (?, ?, gen_random_uuid(), ?, 50000, now() + ?::interval,
                        CASE WHEN ? = 'CONFIRMED' THEN now() END)
                RETURNING id
                """, Long.class, userId, showId, status, expiresIn, status);
        jdbc.update("INSERT INTO booking_seats (booking_id, show_id, seat_id, price_paise) VALUES (?, ?, ?, 50000)",
                id, showId, seatId);
        jdbc.update("UPDATE show_seats SET status = ? WHERE show_id = ? AND seat_id = ?",
                status.equals("CONFIRMED") ? "BOOKED" : "HELD", showId, seatId);
        return id;
    }

    private String status(long bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String seatStatus(long seatId) {
        return jdbc.queryForObject("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?",
                String.class, showId, seatId);
    }

    private int activeSeats(long bookingId) {
        return jdbc.queryForObject("SELECT count(*) FROM booking_seats WHERE booking_id = ? AND active", Integer.class, bookingId);
    }
}
