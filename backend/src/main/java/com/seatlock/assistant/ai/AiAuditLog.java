package com.seatlock.assistant.ai;

import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One {@code ai_requests} row per AI request (V13). Stores the SHA-256 and length of the user's text, never the
 * text itself: on the free tier Google may use prompts to improve its products, and our own table shouldn't
 * become a second copy of what users typed. Auto-committed single INSERT outside any transaction; a failure is
 * logged and swallowed (auditing must not break the feature).
 */
@Component
public class AiAuditLog {

    private static final Logger log = LoggerFactory.getLogger(AiAuditLog.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public AiAuditLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Entry(AiFeature feature, long userId, AiOutcome outcome, String model, Long latencyMs,
                        int inputChars, String inputSha256, Integer promptTokens, Integer completionTokens) {}

    public void record(Entry e) {
        try {
            jdbc.sql("""
                    INSERT INTO ai_requests (user_id, feature, outcome, model, latency_ms, input_chars, input_sha256,
                                             prompt_tokens, completion_tokens, created_at)
                    VALUES (:user, :feature, :outcome, :model, :latency, :chars, :sha, :prompt, :completion, :at)
                    """)
                    .param("user", e.userId())
                    .param("feature", e.feature().name())
                    .param("outcome", e.outcome().name())
                    .param("model", e.model())
                    .param("latency", e.latencyMs())
                    .param("chars", e.inputChars())
                    .param("sha", e.inputSha256())
                    .param("prompt", e.promptTokens())
                    .param("completion", e.completionTokens())
                    .param("at", Timestamp.from(clock.instant()))
                    .update();
        } catch (DataAccessException ex) {
            log.warn("AI audit row not written ({} {}): {}", e.feature(), e.outcome(), ex.toString());
        }
    }
}
