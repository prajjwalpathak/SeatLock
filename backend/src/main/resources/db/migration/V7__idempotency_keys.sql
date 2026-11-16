-- V7 (W3·D3): Idempotency-Key records. A client sends the same key when it retries a request;
-- the first request claims the key (INSERT, unique per user + key), later ones get the stored response.
--
-- status IN_PROGRESS: claimed, operation running (or the app crashed mid-way; see locked_at)
-- status COMPLETED:   finished; response_status + response_body are replayed for retries
--
-- request_hash = SHA-256 of method + path + body: the same key with a different request is rejected (422).
-- locked_at: when the current owner claimed it. An IN_PROGRESS row older than the lease (30 s) may be
--            taken over by a retry (crash recovery), so a key is never stuck until it expires.
-- expires_at: rows are deleted after this (24 h) by a scheduled job.

CREATE TABLE idempotency_keys (
    id               bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          bigint       NOT NULL CONSTRAINT fk_idempotency_user REFERENCES users (id) ON DELETE CASCADE,
    idem_key         varchar(64)  NOT NULL,
    endpoint         varchar(120) NOT NULL,
    request_hash     char(64)     NOT NULL,
    status           varchar(16)  NOT NULL CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status  int,
    response_body    text,
    created_at       timestamptz  NOT NULL,
    locked_at        timestamptz  NOT NULL,
    completed_at     timestamptz,
    expires_at       timestamptz  NOT NULL,
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idem_key),
    CONSTRAINT ck_idempotency_completed CHECK (
        (status = 'IN_PROGRESS' AND response_status IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND response_status BETWEEN 100 AND 599 AND completed_at IS NOT NULL))
);

-- The cleanup job deletes by expiry.
CREATE INDEX ix_idempotency_expires ON idempotency_keys (expires_at);
