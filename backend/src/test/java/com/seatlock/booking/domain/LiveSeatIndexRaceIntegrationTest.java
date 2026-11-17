package com.seatlock.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import testsupport.IntegrationTest;

/**
 * Race C, the last line of defence on its own: Redis, the Lua script and the show_seats compare-and-set are all
 * bypassed. 200 transactions insert a live booking_seats row for the SAME seat straight into Postgres at once.
 * Only the partial unique index {@code ux_booking_seats_live_seat} stands in the way: exactly one may commit.
 *
 * <p>Each transaction pauses briefly after its insert (before COMMIT), so the others pile up behind its
 * uncommitted index entry: Postgres makes them wait, then fails them with 23505 once the winner commits.
 */
@IntegrationTest
class LiveSeatIndexRaceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LiveSeatIndexRaceIntegrationTest.class);
    private static final int TRANSACTIONS = 200;
    private static final int CONNECTIONS = 50; // own pool; Postgres allows 100 in total, the app pool needs 10
    private static final String UNIQUE_VIOLATION = "23505";

    @Autowired DataSource appDataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;

    private HikariDataSource racePool;
    private long userId;
    private long showId;
    private long seatId;

    @BeforeEach
    void setUp() throws SQLException {
        HikariDataSource app = appDataSource.unwrap(HikariDataSource.class); // same container, same credentials
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(app.getJdbcUrl());
        config.setUsername(app.getUsername());
        config.setPassword(app.getPassword());
        config.setMaximumPoolSize(CONNECTIONS);
        config.setMinimumIdle(CONNECTIONS); // open every connection before the race, not during it
        config.setPoolName("race-c");
        racePool = new HikariDataSource(config);

        userId = users.save(new User("race-c-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}unused", "race-c", Role.USER, AuthProvider.LOCAL)).getId();
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id LIMIT 1
                """, Long.class);
        seatId = jdbc.queryForObject("""
                SELECT ss.seat_id FROM show_seats ss
                WHERE ss.show_id = ? AND ss.status = 'AVAILABLE'
                  AND NOT EXISTS (SELECT 1 FROM booking_seats bs WHERE bs.active AND bs.show_id = ss.show_id
                                  AND bs.seat_id = ss.seat_id)
                ORDER BY ss.seat_id LIMIT 1
                """, Long.class, showId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM booking_seats WHERE booking_id IN (SELECT id FROM bookings WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM bookings WHERE user_id = ?", userId);
        racePool.close();
    }

    @Test
    void twoHundredDirectInsertsForOneSeatExactlyOneCommits() throws Exception {
        List<String> outcomes = race(); // "COMMITTED" or "<SQLState> <constraint/message>"
        Map<String, Long> counts = outcomes.stream()
                .collect(Collectors.groupingBy(o -> o, TreeMap::new, Collectors.counting()));
        log.info("race C (200 direct transactions, 1 seat, Redis bypassed): {}", counts);

        assertThat(counts).isEqualTo(Map.of(
                "COMMITTED", 1L,
                UNIQUE_VIOLATION + " ux_booking_seats_live_seat", (long) TRANSACTIONS - 1));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_seats WHERE active AND show_id = ? AND seat_id = ?
                """, Integer.class, showId, seatId)).as("live claims on the seat").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE user_id = ?", Integer.class, userId))
                .as("each loser's whole transaction rolled back, booking row included").isEqualTo(1);
    }

    private List<String> race() throws InterruptedException {
        String[] outcomes = new String[TRANSACTIONS];
        CountDownLatch ready = new CountDownLatch(TRANSACTIONS);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TRANSACTIONS);
        ExecutorService threads = Executors.newFixedThreadPool(TRANSACTIONS);
        try {
            for (int i = 0; i < TRANSACTIONS; i++) {
                int index = i;
                threads.execute(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        outcomes[index] = claimSeat();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        outcomes[index] = "INTERRUPTED";
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            go.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("all transactions finished").isTrue();
            log.info("race C wall time: {} ms", (System.nanoTime() - start) / 1_000_000);
            return new ArrayList<>(List.of(outcomes));
        } finally {
            threads.shutdownNow();
        }
    }

    /** One transaction: a new booking + its live seat row, held open briefly, then COMMIT. */
    private String claimSeat() {
        try (Connection c = racePool.getConnection()) {
            c.setAutoCommit(false);
            try {
                long bookingId;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO bookings (user_id, show_id, hold_id, status, total_paise, expires_at)
                        VALUES (?, ?, gen_random_uuid(), 'HELD', 100, now() + interval '5 minutes') RETURNING id
                        """)) {
                    ps.setLong(1, userId);
                    ps.setLong(2, showId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        bookingId = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO booking_seats (booking_id, show_id, seat_id, price_paise) VALUES (?, ?, ?, 100)")) {
                    ps.setLong(1, bookingId);
                    ps.setLong(2, showId);
                    ps.setLong(3, seatId);
                    ps.executeUpdate(); // a loser blocks HERE while the winner's entry is uncommitted
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT pg_sleep(0.02)")) {
                    ps.execute(); // keep the window open so concurrent inserters really queue up
                }
                c.commit();
                return "COMMITTED";
            } catch (SQLException e) {
                c.rollback();
                return describe(e);
            }
        } catch (SQLException e) {
            return "CONNECTION " + e.getSQLState() + " " + e.getMessage();
        }
    }

    private static String describe(SQLException e) {
        String message = String.valueOf(e.getMessage());
        String constraint = message.contains("ux_booking_seats_live_seat") ? "ux_booking_seats_live_seat" : message;
        return e.getSQLState() + " " + constraint;
    }
}
