package com.seatlock.booking.hold;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OPTIMISTIC strategy: holds in Postgres (V11), no Redis on the hold path. Nothing is locked between requests; a
 * claim is a conditional upsert that only takes a seat whose previous claim has expired. Concurrent claims of one
 * seat serialize on its row (the second waits for the first to commit, then sees a live claim), so exactly one wins.
 * All-or-nothing: if any seat is taken, the transaction rolls back and the taken seats are reported.
 * One live hold per user and show is serialized with a transaction-scoped advisory lock.
 */
@Component
@ConditionalOnProperty(name = "seatlock.holds.strategy", havingValue = "optimistic")
public class OptimisticHoldStore implements SeatLockingStrategy {

    private static final Logger log = LoggerFactory.getLogger(OptimisticHoldStore.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OptimisticHoldStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        log.info("Seat locking strategy: optimistic");
    }

    @Override
    public String name() {
        return "optimistic";
    }

    @Override
    public HoldAttempt tryHold(String holdId, long userId, long showId, List<Long> seatIds, Duration ttl,
                               Instant expiresAt) {
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("seatIds must not be empty");
        }
        return tx.execute(status -> {
            Timestamp now = Timestamp.from(clock.instant());
            // One hold per user and show: concurrent requests of the same user wait here (lock ends with the tx).
            jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended('hold:' || :userId || ':' || :showId, 0))) l")
                    .param("userId", userId).param("showId", showId).query(Integer.class).single();

            Optional<UUID> existing = jdbc.sql("""
                            SELECT hold_id FROM seat_holds
                            WHERE user_id = :userId AND show_id = :showId AND expires_at > :now LIMIT 1""")
                    .param("userId", userId).param("showId", showId).param("now", now)
                    .query(UUID.class).optional();
            if (existing.isPresent()) {
                return new HoldAttempt.HoldExists(existing.get().toString());
            }

            UUID id = UUID.fromString(holdId);
            Timestamp expires = Timestamp.from(expiresAt);
            jdbc.sql("""
                            INSERT INTO seat_holds (hold_id, user_id, show_id, seat_ids, expires_at)
                            VALUES (:id, :userId, :showId, :seats, :expires)""")
                    .param("id", id).param("userId", userId).param("showId", showId)
                    .param("seats", seatIds.toArray(Long[]::new)).param("expires", expires)
                    .update();

            // Sorted so two holds claiming overlapping seats lock rows in the same order (no deadlock).
            List<Long> sorted = seatIds.stream().sorted().toList();
            StringBuilder values = new StringBuilder();
            Map<String, Object> params = new LinkedHashMap<>();
            for (int i = 0; i < sorted.size(); i++) {
                values.append(i == 0 ? "" : ", ").append("(:show, :s").append(i).append(", :id, :expires)");
                params.put("s" + i, sorted.get(i));
            }
            List<Long> claimed = jdbc.sql("""
                            INSERT INTO seat_hold_claims (show_id, seat_id, hold_id, expires_at) VALUES %s
                            ON CONFLICT (show_id, seat_id) DO UPDATE
                                SET hold_id = EXCLUDED.hold_id, expires_at = EXCLUDED.expires_at,
                                    version = seat_hold_claims.version + 1
                                WHERE seat_hold_claims.expires_at <= :now
                            RETURNING seat_id""".formatted(values))
                    .params(params).param("show", showId).param("id", id).param("expires", expires).param("now", now)
                    .query(Long.class).list();

            if (claimed.size() != seatIds.size()) {
                status.setRollbackOnly(); // all or nothing: no seat stays claimed, the hold row disappears
                Set<Long> got = new HashSet<>(claimed);
                return new HoldAttempt.SeatsTaken(seatIds.stream().filter(s -> !got.contains(s)).toList());
            }
            return new HoldAttempt.Held(holdId);
        });
    }

    @Override
    public Optional<HoldRecord> find(String holdId) {
        UUID id;
        try {
            id = UUID.fromString(holdId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT user_id, show_id, seat_ids, expires_at FROM seat_holds
                        WHERE hold_id = :id AND expires_at > :now""")
                .param("id", id).param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> {
                    Array array = rs.getArray("seat_ids");
                    List<Long> seats = Arrays.stream((Long[]) array.getArray()).toList();
                    return new HoldRecord(holdId, rs.getLong("user_id"), rs.getLong("show_id"), seats,
                            rs.getTimestamp("expires_at").toInstant());
                })
                .optional();
    }

    @Override
    public int release(HoldRecord hold) {
        UUID id = UUID.fromString(hold.holdId());
        Integer released = tx.execute(status -> {
            int claims = jdbc.sql("DELETE FROM seat_hold_claims WHERE hold_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM seat_holds WHERE hold_id = :id").param("id", id).update();
            return claims;
        });
        return released == null ? 0 : released;
    }

    @Override
    public Set<Long> heldSeatIds(long showId, Collection<Long> seatIds) {
        if (seatIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                        SELECT seat_id FROM seat_hold_claims
                        WHERE show_id = :show AND seat_id IN (:ids) AND expires_at > :now""")
                .param("show", showId).param("ids", new ArrayList<>(seatIds))
                .param("now", Timestamp.from(clock.instant()))
                .query(Long.class).list());
    }

    /** Hourly: drop holds that expired more than an hour ago (their claims go with them). Idempotent. */
    @org.springframework.scheduling.annotation.Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public void deleteExpired() {
        int n = jdbc.sql("DELETE FROM seat_holds WHERE expires_at < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(Duration.ofHours(1)))).update();
        if (n > 0) {
            log.info("Deleted {} expired optimistic holds", n);
        }
    }
}
