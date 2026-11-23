-- V13 (W7·D1): one row per AI request, for quotas review, fallback rates and cost tracking (ADR-016).
--
-- Never stores what the user typed: only its length and SHA-256 (repeats are visible, contents are not).
-- outcome = how the request ended; everything but OK means the feature used its non-AI fallback.
-- latency_ms is null when no call was made (DISABLED, quotas, circuit open, busy).
-- Rows older than seatlock.ai.audit-retention (30 days) are deleted daily by AiRequestCleanupJob.

CREATE TABLE ai_requests (
    id                 bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            bigint       NOT NULL CONSTRAINT fk_ai_requests_user REFERENCES users (id),
    feature            varchar(24)  NOT NULL,
    outcome            varchar(16)  NOT NULL,
    model              varchar(64),
    latency_ms         integer,
    input_chars        integer      NOT NULL,
    input_sha256       char(64)     NOT NULL,
    prompt_tokens      integer,
    completion_tokens  integer,
    created_at         timestamptz  NOT NULL,
    CONSTRAINT ck_ai_requests_feature CHECK (feature IN ('PING', 'SEAT_FINDER', 'REFUND_ASSISTANT')),
    CONSTRAINT ck_ai_requests_outcome CHECK (outcome IN ('OK', 'DISABLED', 'USER_QUOTA', 'QUOTA', 'CIRCUIT_OPEN',
                                                         'TIMEOUT', 'BUSY', 'ERROR', 'INVALID_OUTPUT')),
    CONSTRAINT ck_ai_requests_numbers CHECK (input_chars >= 0
                                             AND (latency_ms IS NULL OR latency_ms >= 0)
                                             AND (prompt_tokens IS NULL OR prompt_tokens >= 0)
                                             AND (completion_tokens IS NULL OR completion_tokens >= 0))
);

-- Cleanup (created_at < cutoff) and "last 24 h" reviews
CREATE INDEX ix_ai_requests_created ON ai_requests (created_at);
