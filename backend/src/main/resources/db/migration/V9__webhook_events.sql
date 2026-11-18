-- V9 (W4·D3): webhook deliveries we have processed, for de-duplication.
--
-- Razorpay delivers at least once (retries on timeouts / non-2xx) and may send the same event again.
-- The row is inserted in the SAME transaction as the processing (payment captured + booking confirmed):
--   * duplicate delivery → INSERT ... ON CONFLICT DO NOTHING inserts nothing → skipped, 200
--   * processing fails   → the whole transaction rolls back, the row disappears, Razorpay's retry runs again
-- event_id is Razorpay's X-Razorpay-Event-Id header (or a SHA-256 of the body if it is missing).

CREATE TABLE webhook_events (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider        varchar(16)  NOT NULL,
    event_id        varchar(128) NOT NULL,
    event_type      varchar(64)  NOT NULL,
    payload_sha256  char(64)     NOT NULL,
    outcome         varchar(32)  NOT NULL,
    received_at     timestamptz  NOT NULL,
    CONSTRAINT uq_webhook_events_provider_event UNIQUE (provider, event_id),
    CONSTRAINT ck_webhook_events_provider CHECK (provider IN ('RAZORPAY'))
);
