package com.seatlock.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import testsupport.IntegrationTest;

/**
 * Pins the isolation-level behaviour the oversell guards rely on (CORRECTNESS section 3), with two real connections:
 * <ul>
 *   <li>the app runs at Postgres' default READ COMMITTED;</li>
 *   <li>a compare-and-set {@code UPDATE … WHERE status = 'AVAILABLE'} that waits for another transaction's row lock
 *       re-checks its WHERE on the committed row and updates 0 rows (no lost update, no error, no retry);</li>
 *   <li>the same statement at REPEATABLE READ fails with 40001 instead (would need a retry loop: why we don't use it);</li>
 *   <li>{@code SKIP LOCKED} skips a locked row immediately instead of waiting (the sweepers' work splitting).</li>
 * </ul>
 */
@IntegrationTest
class IsolationIntegrationTest {

    private static final String CAS = """
            UPDATE show_seats SET status = 'HELD'
            WHERE show_id = ? AND seat_id = ? AND status = 'AVAILABLE'
            """;

    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;

    private long showId;
    private long seatA;
    private long seatB;
    private final List<Connection> open = new ArrayList<>();

    @BeforeEach
    void pickTwoAvailableSeats() {
        record Pick(long showId, long seatA, long seatB) {}
        Pick pick = jdbc.sql("""
                        SELECT show_id, min(seat_id) AS a, max(seat_id) AS b FROM (
                          SELECT ss.show_id, ss.seat_id, row_number() OVER (PARTITION BY ss.show_id ORDER BY ss.seat_id) AS r
                          FROM show_seats ss JOIN shows s ON s.id = ss.show_id
                          WHERE ss.status = 'AVAILABLE' AND s.starts_at > now() + interval '2 days') x
                        WHERE r <= 2 GROUP BY show_id HAVING count(*) = 2 ORDER BY show_id DESC LIMIT 1
                        """)
                .query((rs, n) -> new Pick(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .single();
        showId = pick.showId();
        seatA = pick.seatA();
        seatB = pick.seatB();
    }

    @AfterEach
    void restore() throws SQLException {
        for (Connection c : open) {
            if (!c.getAutoCommit()) {
                c.rollback();
            }
            c.close();
        }
        jdbc.sql("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = :show AND seat_id IN (:a, :b)")
                .param("show", showId).param("a", seatA).param("b", seatB).update();
    }

    @Test
    void theAppRunsAtReadCommitted() {
        assertThat(jdbc.sql("SHOW transaction_isolation").query(String.class).single()).isEqualTo("read committed");
    }

    @Test
    void readCommittedCompareAndSetWaitsThenSeesTheCommitAndUpdatesNothing() throws Exception {
        Connection first = tx(Connection.TRANSACTION_READ_COMMITTED);
        Connection second = tx(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(cas(first, seatA)).isEqualTo(1);                       // first claims, row now locked

        CompletableFuture<Integer> waiting = CompletableFuture.supplyAsync(() -> casUnchecked(second, seatA));
        assertThatThrownBy(() -> waiting.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        first.commit();                                                   // HELD is now committed
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isZero();            // WHERE re-checked on the new row: 0 rows
        second.commit();
        assertThat(status(seatA)).isEqualTo("HELD");
    }

    @Test
    void repeatableReadWouldFailWithASerializationErrorInstead() throws Exception {
        Connection first = tx(Connection.TRANSACTION_READ_COMMITTED);
        Connection second = tx(Connection.TRANSACTION_REPEATABLE_READ);
        snapshot(second);                                                 // RR snapshot taken before first commits
        assertThat(cas(first, seatA)).isEqualTo(1);

        CompletableFuture<Integer> waiting = CompletableFuture.supplyAsync(() -> casUnchecked(second, seatA));
        first.commit();

        assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("40001"));
    }

    @Test
    void skipLockedSkipsALockedRowWithoutWaiting() throws Exception {
        Connection first = tx(Connection.TRANSACTION_READ_COMMITTED);
        Connection second = tx(Connection.TRANSACTION_READ_COMMITTED);
        lockForUpdate(first, seatA);

        long started = System.nanoTime();
        List<Long> got = new ArrayList<>();
        try (PreparedStatement ps = second.prepareStatement("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND seat_id IN (?, ?)
                ORDER BY seat_id FOR UPDATE SKIP LOCKED
                """)) {
            ps.setLong(1, showId);
            ps.setLong(2, seatA);
            ps.setLong(3, seatB);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    got.add(rs.getLong(1));
                }
            }
        }
        assertThat(got).containsExactly(seatB);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000);
    }

    // ------------------------------------------------------------------ helpers

    private Connection tx(int isolation) throws SQLException {
        Connection c = dataSource.getConnection();
        open.add(c);
        c.setAutoCommit(false);
        c.setTransactionIsolation(isolation);
        try (PreparedStatement ps = c.prepareStatement("SET LOCAL lock_timeout = '10s'")) {
            ps.execute();   // a bug can't hang the suite
        }
        return c;
    }

    private int cas(Connection c, long seatId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CAS)) {
            ps.setLong(1, showId);
            ps.setLong(2, seatId);
            return ps.executeUpdate();
        }
    }

    private int casUnchecked(Connection c, long seatId) {
        try {
            return cas(c, seatId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void snapshot(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?")) {
            ps.setLong(1, showId);
            ps.setLong(2, seatA);
            ps.executeQuery().close();
        }
    }

    private void lockForUpdate(Connection c, long seatId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM show_seats WHERE show_id = ? AND seat_id = ? FOR UPDATE")) {
            ps.setLong(1, showId);
            ps.setLong(2, seatId);
            ps.executeQuery().close();
        }
    }

    private String status(long seatId) {
        return jdbc.sql("SELECT status FROM show_seats WHERE show_id = :show AND seat_id = :seat")
                .param("show", showId).param("seat", seatId).query(String.class).single();
    }
}
