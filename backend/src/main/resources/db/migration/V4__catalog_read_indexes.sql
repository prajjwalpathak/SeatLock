-- V4__catalog_read_indexes.sql: indexes chosen from the W1·D5 baseline plans (docs/CORRECTNESS.md §2.1).
-- Plain CREATE INDEX (takes a write lock) is fine at this size. On a large live table we would use
-- CREATE INDEX CONCURRENTLY in a separate non-transactional Flyway migration.

-- "From price" = min(price_paise) per show: lets Postgres read only the first index entry
-- (min/max optimisation, often index-only) instead of every seat row of the show.
CREATE INDEX ix_show_seats_show_price ON show_seats (show_id, price_paise);

-- An event's next show: jump to the event's shows already ordered by time, take the first.
-- Supersedes ix_shows_event (its prefix), which still covers FK lookups on event_id.
CREATE INDEX ix_shows_event_starts ON shows (event_id, starts_at);
DROP INDEX ix_shows_event;

-- Deliberately NOT indexed: show_seats.status changes on every hold/booking; leaving it
-- unindexed keeps those updates HOT (heap-only tuple, no index writes).