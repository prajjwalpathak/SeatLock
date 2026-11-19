-- V11 (W4·D6): seat holds in Postgres, for the OPTIMISTIC SeatLockingStrategy (seatlock.holds.strategy=optimistic).
-- The default strategy (REDIS_HOLD) doesn't touch these tables.
--
-- seat_holds: one row per hold (what Redis keeps in hold:id:{holdId}).
-- seat_hold_claims: one row per (show, seat), reused across holds. A seat is held while expires_at is in the future.
-- Claiming is a conditional upsert (INSERT ... ON CONFLICT ... DO UPDATE ... WHERE expires_at <= now): no lock is
-- held between requests; a concurrent claim of the same seat waits for the first to commit, then sees a live claim
-- and changes nothing. version counts how often a seat changed hands (conflict statistics for the benchmark).

CREATE TABLE seat_holds (
    hold_id     uuid         PRIMARY KEY,
    user_id     bigint       NOT NULL CONSTRAINT fk_seat_holds_user REFERENCES users (id),
    show_id     bigint       NOT NULL CONSTRAINT fk_seat_holds_show REFERENCES shows (id),
    seat_ids    bigint[]     NOT NULL,
    expires_at  timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_seat_holds_seats CHECK (cardinality(seat_ids) BETWEEN 1 AND 10)
);
CREATE INDEX ix_seat_holds_user_show ON seat_holds (user_id, show_id, expires_at);
CREATE INDEX ix_seat_holds_expires ON seat_holds (expires_at);

CREATE TABLE seat_hold_claims (
    show_id     bigint       NOT NULL,
    seat_id     bigint       NOT NULL,
    hold_id     uuid         NOT NULL CONSTRAINT fk_claims_hold REFERENCES seat_holds (hold_id) ON DELETE CASCADE,
    expires_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_seat_hold_claims PRIMARY KEY (show_id, seat_id),
    CONSTRAINT fk_claims_show_seat FOREIGN KEY (show_id, seat_id) REFERENCES show_seats (show_id, seat_id)
);
CREATE INDEX ix_seat_hold_claims_hold ON seat_hold_claims (hold_id);
