package com.seatlock.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import testsupport.IntegrationTest;

/** V13 table, the audit writer and the retention job against real Postgres. */
@IntegrationTest
class AiAuditLogIntegrationTest {

    @Autowired AiAuditLog audit;
    @Autowired AiRequestCleanupJob cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private long userId;

    @BeforeEach
    void demoUser() {
        userId = jdbc.queryForObject("SELECT min(id) FROM users", Long.class);
        jdbc.update("DELETE FROM ai_requests");
    }

    @Test
    void entryIsStoredWithHashAndLengthOnly() {
        audit.record(new AiAuditLog.Entry(AiFeature.SEAT_FINDER, userId, AiOutcome.OK, "test-model", 812L,
                17, AiGateway.sha256("2 aisle seats ok?"), 40, 12));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_requests");
        assertThat(row).containsEntry("feature", "SEAT_FINDER").containsEntry("outcome", "OK")
                .containsEntry("model", "test-model").containsEntry("latency_ms", 812)
                .containsEntry("input_chars", 17).containsEntry("prompt_tokens", 40)
                .containsEntry("completion_tokens", 12);
        assertThat(row.get("input_sha256")).isEqualTo(AiGateway.sha256("2 aisle seats ok?"));
        assertThat(row.values()).doesNotContain("2 aisle seats ok?");
    }

    @Test
    void fallbackWithoutACallHasNoLatencyOrModel() {
        audit.record(new AiAuditLog.Entry(AiFeature.PING, userId, AiOutcome.DISABLED, null, null,
                2, AiGateway.sha256("hi"), null, null));

        Map<String, Object> row = jdbc.queryForMap("SELECT model, latency_ms, outcome FROM ai_requests");
        assertThat(row.get("model")).isNull();
        assertThat(row.get("latency_ms")).isNull();
        assertThat(row).containsEntry("outcome", "DISABLED");
    }

    @Test
    void auditFailureIsSwallowed() {
        // unknown user → FK violation inside record(): logged, not thrown (auditing never breaks a feature)
        audit.record(new AiAuditLog.Entry(AiFeature.PING, -1L, AiOutcome.OK, "m", 1L, 1, AiGateway.sha256("x"), null, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_requests", Long.class)).isZero();
    }

    @Test
    void checksRejectUnknownValues() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_requests (user_id, feature, outcome, input_chars, input_sha256, created_at)
                VALUES (?, 'CHAT', 'OK', 1, ?, now())""", userId, "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_requests (user_id, feature, outcome, input_chars, input_sha256, latency_ms, created_at)
                VALUES (?, 'PING', 'OK', 1, ?, -5, now())""", userId, "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cleanupDeletesOnlyRowsPastRetention() {
        String insert = """
                INSERT INTO ai_requests (user_id, feature, outcome, input_chars, input_sha256, created_at)
                VALUES (?, 'PING', 'OK', 1, ?, ?)""";
        jdbc.update(insert, userId, "0".repeat(64), Timestamp.from(clock.instant().minus(Duration.ofDays(31))));
        jdbc.update(insert, userId, "1".repeat(64), Timestamp.from(clock.instant().minus(Duration.ofDays(29))));

        assertThat(cleanup.deleteOld()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT input_sha256 FROM ai_requests", String.class)).isEqualTo("1".repeat(64));
    }
}
