package com.seatlock.common.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Postgres-backed idempotency keys. Every method is one auto-committed statement (no surrounding
 * transaction): a claim must be visible to concurrent requests the moment it is made.
 * The unique constraint (user_id, idem_key) decides races: exactly one INSERT wins.
 */
@Repository
public class IdempotencyStore {

    /** Result of trying to claim a key. */
    public sealed interface Claim {
        /** We own the key now; run the operation, then {@link #complete} or {@link #release}. */
        record Claimed(long id) implements Claim {}
        /** Someone (maybe an earlier attempt of ours) already holds this key. */
        record Existing(IdempotencyRecord record) implements Claim {}
    }

    private static final String CLAIM_SQL = """
            INSERT INTO idempotency_keys (user_id, idem_key, endpoint, request_hash, status,
                                          created_at, locked_at, expires_at)
            VALUES (:userId, :key, :endpoint, :hash, 'IN_PROGRESS', :now, :now, :expiresAt)
            ON CONFLICT (user_id, idem_key) DO NOTHING
            RETURNING id
            """;

    private static final String FIND_SQL = """
            SELECT id, user_id, idem_key, endpoint, request_hash, status, response_status, response_body,
                   locked_at, expires_at
            FROM idempotency_keys
            WHERE user_id = :userId AND idem_key = :key
            """;

    private final JdbcClient jdbc;

    public IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Claim claim(long userId, String key, String endpoint, String requestHash, Instant now, Duration ttl) {
        Optional<Long> id = jdbc.sql(CLAIM_SQL)
                .param("userId", userId)
                .param("key", key)
                .param("endpoint", endpoint)
                .param("hash", requestHash)
                .param("now", utc(now))
                .param("expiresAt", utc(now.plus(ttl)))
                .query(Long.class)
                .optional();
        if (id.isPresent()) {
            return new Claim.Claimed(id.get());
        }
        // Lost the race or a retry. The row can't disappear in between except by expiry cleanup
        // (24 h old) or a release; then simply claim again.
        return find(userId, key)
                .<Claim>map(Claim.Existing::new)
                .orElseGet(() -> claim(userId, key, endpoint, requestHash, now, ttl));
    }

    public Optional<IdempotencyRecord> find(long userId, String key) {
        return jdbc.sql(FIND_SQL)
                .param("userId", userId)
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("idem_key"),
                        rs.getString("endpoint"),
                        rs.getString("request_hash"),
                        IdempotencyRecord.Status.valueOf(rs.getString("status")),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body"),
                        rs.getObject("locked_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Crash recovery: take over an IN_PROGRESS claim whose owner hasn't finished within the lease.
     * The WHERE clause makes this a compare-and-set: of several retries, only one wins.
     */
    public boolean takeOver(long id, Instant now, Duration lease) {
        return jdbc.sql("""
                        UPDATE idempotency_keys SET locked_at = :now
                        WHERE id = :id AND status = 'IN_PROGRESS' AND locked_at <= :staleBefore
                        """)
                .param("id", id)
                .param("now", utc(now))
                .param("staleBefore", utc(now.minus(lease)))
                .update() == 1;
    }

    /** Stores the final response; later requests with this key get it replayed. */
    public void complete(long id, int responseStatus, String responseBody, Instant now) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_status = :status, response_body = :body, completed_at = :now
                        WHERE id = :id AND status = 'IN_PROGRESS'
                        """)
                .param("id", id)
                .param("status", responseStatus)
                .param("body", responseBody)
                .param("now", utc(now))
                .update();
    }

    /** Gives the key back (the operation failed in a retryable way): the next request runs again. */
    public void release(long id) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE id = :id AND status = 'IN_PROGRESS'")
                .param("id", id)
                .update();
    }

    /** Deletes expired keys; returns how many. */
    public int deleteExpired(Instant now) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at <= :now")
                .param("now", utc(now))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC); // driver binds OffsetDateTime, not Instant
    }
}
