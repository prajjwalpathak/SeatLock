-- Catalog read-query plans. Re-run after every index change and compare.
-- Usage (repo root, PowerShell):
--   Get-Content docs\explain\catalog-queries.sql -Raw |
--     docker exec -i seatlock-postgres psql -U seatlock -d seatlock -q |
--     Set-Content -Encoding utf8 docs\explain\<name>.txt
-- The SQL mirrors EventSearchRepository / ShowQueryRepository (first page, no filters).

VACUUM ANALYZE;

SELECT min(e.id) AS eid FROM events e WHERE e.status = 'PUBLISHED' \gset
SELECT min(s.id) AS sid FROM shows s JOIN events e ON e.id = s.event_id
 WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' \gset

-- ---------- warm-up (output discarded) ----------
\o /dev/null
SELECT e.id, ns.starts_at, ns.from_price_paise FROM events e
JOIN LATERAL (SELECT s.starts_at, (SELECT min(ss.price_paise) FROM show_seats ss WHERE ss.show_id = s.id) AS from_price_paise
              FROM shows s WHERE s.event_id = e.id AND s.status = 'SCHEDULED' AND s.starts_at >= now()
              ORDER BY s.starts_at, s.id LIMIT 1) ns ON true
WHERE e.status = 'PUBLISHED' ORDER BY ns.starts_at, e.id LIMIT 21;
SELECT * FROM show_seats ss JOIN seats st ON st.id = ss.seat_id WHERE ss.show_id = :sid;
\o

\echo '==================== Q1: event list, first page (GET /api/v1/events) ===================='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT e.id, e.title, e.category, e.language, e.duration_minutes,
       ns.starts_at, ns.venue_name, ns.city, ns.from_price_paise
FROM events e
JOIN LATERAL (
    SELECT s.starts_at, v.name AS venue_name, v.city,
           (SELECT min(ss.price_paise) FROM show_seats ss WHERE ss.show_id = s.id) AS from_price_paise
    FROM shows s
    JOIN venues v ON v.id = s.venue_id
    WHERE s.event_id = e.id
      AND s.status = 'SCHEDULED'
      AND s.starts_at >= now()
    ORDER BY s.starts_at, s.id
    LIMIT 1
) ns ON true
WHERE e.status = 'PUBLISHED'
ORDER BY ns.starts_at, e.id
LIMIT 21;

\echo '==================== Q2: upcoming shows of one event (GET /api/v1/events/{id}) ===================='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT s.id, s.starts_at, v.id AS venue_id, v.name AS venue_name, v.city,
       (SELECT min(ss.price_paise) FROM show_seats ss WHERE ss.show_id = s.id) AS from_price_paise
FROM shows s
JOIN venues v ON v.id = s.venue_id
WHERE s.event_id = :eid
  AND s.status = 'SCHEDULED'
  AND s.starts_at >= now()
ORDER BY s.starts_at, s.id;

\echo '==================== Q3: seat map of one show (GET /api/v1/shows/{id}/seats) ===================='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT st.id AS seat_id, st.section, st.row_label, st.seat_number,
       st.grid_row, st.grid_col, st.is_aisle, ss.price_paise, ss.status
FROM show_seats ss
JOIN seats st ON st.id = ss.seat_id
WHERE ss.show_id = :sid
ORDER BY st.grid_row, st.grid_col;