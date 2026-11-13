-- V3__event_search.sql: Postgres full-text search for events.
-- A generated, weighted tsvector (title A > description B > category/language C),
-- kept in sync by Postgres itself, plus a GIN index for fast @@ matching.
-- English config everywhere so stemming matches between documents and queries.

ALTER TABLE events
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(category, '') || ' ' || coalesce(language, '')), 'C')
        ) STORED;

CREATE INDEX ix_events_search ON events USING gin (search_vector);