-- V6 (W2·D4): a show occupies its venue from starts_at to ends_at (event duration + 30 min
-- turnover), and the DATABASE guarantees that two scheduled shows never overlap at one venue.
-- Same idea as ux_booking_seats_live_seat for seats: the rule holds even if two organizers
-- create shows at the same moment, or the application has a bug.

-- btree_gist lets a GiST index combine "=" on venue_id with "&&" (overlaps) on a time range.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE shows ADD COLUMN ends_at timestamptz;

UPDATE shows s
SET ends_at = s.starts_at + make_interval(mins => e.duration_minutes + 30)
FROM events e
WHERE e.id = s.event_id;

ALTER TABLE shows ALTER COLUMN ends_at SET NOT NULL;

ALTER TABLE shows
    ADD CONSTRAINT ck_shows_ends_after_start CHECK (ends_at > starts_at);

-- '[)' = half-open: a show ending at 22:00 doesn't clash with one starting at 22:00.
-- Cancelled shows free the slot (partial constraint).
ALTER TABLE shows
    ADD CONSTRAINT ex_shows_venue_no_overlap
        EXCLUDE USING gist (venue_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
        WHERE (status = 'SCHEDULED');
