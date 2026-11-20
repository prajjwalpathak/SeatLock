-- Write-path / per-user query plans AT VOLUME (W6·D5). One transaction, ROLLED BACK at the end: the volume data and
-- the candidate indexes never stay in the dev database. Run BEFORE V12 is applied (the script checks).
-- Usage (repo root, PowerShell; stop the apps first so nothing waits on the locks this transaction takes):
--   docker stop seatlock-app-1 seatlock-app-2
--   Get-Content docs\explain\write-paths.sql -Raw |
--     docker exec -i seatlock-postgres psql -U seatlock -d seatlock -q |
--     Set-Content -Encoding utf8 docs\explain\03-write-paths.txt
-- The SQL mirrors BookingRepository.lockExpiredIds, MyBookingsQuery, ShowQueryRepository.seatsById,
-- ShowSeatStatusService (compare-and-set) and SeatInvariantMonitor.
\set ON_ERROR_STOP on

SELECT to_regclass('ix_bookings_unpaid_expires') IS NULL AS before_v12 \gset
\if :before_v12
\else
  \echo 'V12 is already applied (ix_bookings_unpaid_expires exists): run this on a database before V12.'
  \quit
\endif

BEGIN;
SET LOCAL max_parallel_workers_per_gather = 0;   -- single-process plans: comparable on a laptop

\echo '==================== volume (rolled back at the end) ===================='
-- 2,001 users; the last one is the "power user" with 5,000 bookings, the others ~100 each.
INSERT INTO users (email, password_hash, display_name)
SELECT 'explain-' || g || '@seatlock.test', '!', 'Explain ' || g FROM generate_series(1, 2001) g;
SELECT min(id) AS u0, max(id) AS heavy FROM users WHERE email LIKE 'explain-%@seatlock.test' \gset

CREATE TEMP TABLE vol_seats ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY show_id, seat_id) AS n, show_id, seat_id, price_paise FROM show_seats;
SELECT count(*) AS nseats FROM vol_seats \gset

-- 205,000 bookings over ~60 days. Mix: 0.2 % unpaid and DUE (the sweeper's work), 0.8 % unpaid not due yet,
-- 12 % CONFIRMED, 4 % CANCELLED, the rest EXPIRED: the shape of a ticketing table after a few busy weeks.
INSERT INTO bookings (user_id, show_id, hold_id, status, total_paise, expires_at, confirmed_at, cancelled_at,
                      created_at, updated_at)
SELECT CASE WHEN g <= 5000 THEN :heavy ELSE :u0 + (g % 2000) END,
       vs.show_id,
       md5('explain-' || g)::uuid,
       CASE WHEN g % 1000 < 2  THEN 'HELD'
            WHEN g % 1000 < 6  THEN 'HELD'
            WHEN g % 1000 < 10 THEN 'PAYMENT_PENDING'
            WHEN g % 100  < 12 THEN 'CONFIRMED'
            WHEN g % 100  < 16 THEN 'CANCELLED'
            ELSE 'EXPIRED' END,
       vs.price_paise,
       CASE WHEN g % 1000 < 2  THEN now() - interval '1 minute' * (1 + g % 30)     -- due
            WHEN g % 1000 < 10 THEN now() + interval '1 minute' * (1 + g % 8)      -- not yet due
            ELSE now() - interval '25 seconds' * g + interval '8 minutes' END,
       CASE WHEN g % 1000 >= 10 AND g % 100 < 12 THEN now() - interval '25 seconds' * g + interval '3 minutes' END,
       CASE WHEN g % 1000 >= 10 AND g % 100 >= 12 AND g % 100 < 16 THEN now() - interval '25 seconds' * g + interval '2 minutes' END,
       now() - interval '25 seconds' * g,
       now() - interval '25 seconds' * g
FROM generate_series(1, 205000) g
JOIN vol_seats vs ON vs.n = 1 + (g % :nseats);

-- One seat per booking, inactive (historical): keeps the live-seat index and the invariant untouched.
INSERT INTO booking_seats (booking_id, show_id, seat_id, price_paise, active)
SELECT b.id, b.show_id, vs.seat_id, vs.price_paise, false
FROM generate_series(1, 205000) g
JOIN vol_seats vs ON vs.n = 1 + (g % :nseats)
JOIN bookings b ON b.hold_id = md5('explain-' || g)::uuid;

ANALYZE users;
ANALYZE bookings;
ANALYZE booking_seats;
SELECT count(*) AS bookings, count(*) FILTER (WHERE status IN ('HELD', 'PAYMENT_PENDING') AND expires_at <= now()) AS due,
       count(*) FILTER (WHERE user_id = :heavy) AS heavy_user_bookings FROM bookings;

-- A show with two AVAILABLE seats for the seat-level queries
SELECT show_id AS sid, min(seat_id) AS s1, max(seat_id) AS s2 FROM (
  SELECT show_id, seat_id, row_number() OVER (PARTITION BY show_id ORDER BY seat_id) AS r
  FROM show_seats WHERE status = 'AVAILABLE') x
WHERE r <= 2 GROUP BY show_id HAVING count(*) = 2 ORDER BY show_id LIMIT 1 \gset

\echo '==================== BEFORE (V1 indexes: ix_bookings_user only) ===================='
\o /dev/null
SELECT id FROM bookings WHERE status IN ('HELD', 'PAYMENT_PENDING') AND expires_at <= now() ORDER BY expires_at, id LIMIT 100;
SELECT b.public_id FROM bookings b WHERE b.user_id = :heavy ORDER BY b.created_at DESC, b.id DESC LIMIT 10;
\o

\echo '---------- W1: expiry sweeper batch (BookingRepository.lockExpiredIds) ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM bookings
WHERE status IN ('HELD', 'PAYMENT_PENDING') AND expires_at <= now()
ORDER BY expires_at, id
LIMIT 100
FOR UPDATE SKIP LOCKED;

\echo '---------- W2: My bookings, first page, power user (5,000 bookings) ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT b.public_id, b.status, b.show_id, b.total_paise, b.currency, b.expires_at, b.created_at,
       (SELECT count(*) FROM booking_seats bs WHERE bs.booking_id = b.id) AS seat_count
FROM bookings b WHERE b.user_id = :heavy
ORDER BY b.created_at DESC, b.id DESC LIMIT 10 OFFSET 0;

\echo '---------- W3: My bookings, page 50 (OFFSET 490), power user ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT b.public_id, b.status, b.show_id, b.total_paise, b.currency, b.expires_at, b.created_at,
       (SELECT count(*) FROM booking_seats bs WHERE bs.booking_id = b.id) AS seat_count
FROM bookings b WHERE b.user_id = :heavy
ORDER BY b.created_at DESC, b.id DESC LIMIT 10 OFFSET 490;

\echo '---------- W4: My bookings, first page, regular user (~100 bookings) ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT b.public_id, b.status, b.show_id, b.total_paise, b.currency, b.expires_at, b.created_at,
       (SELECT count(*) FROM booking_seats bs WHERE bs.booking_id = b.id) AS seat_count
FROM bookings b WHERE b.user_id = :u0 + 1
ORDER BY b.created_at DESC, b.id DESC LIMIT 10 OFFSET 0;

\echo '---------- W5: My bookings total, power user ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM bookings WHERE user_id = :heavy;

\echo '---------- W6: hold pre/post check, seats by id (ShowQueryRepository.seatsById) ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT st.id AS seat_id, st.section, st.row_label, st.seat_number, ss.price_paise, ss.status
FROM show_seats ss JOIN seats st ON st.id = ss.seat_id
WHERE ss.show_id = :sid AND ss.seat_id IN (:s1, :s2)
ORDER BY st.grid_row, st.grid_col;

\echo '---------- W7: seat status compare-and-set (ShowSeatStatusService), rolled back ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
UPDATE show_seats SET status = 'HELD'
WHERE show_id = :sid AND seat_id IN (:s1, :s2) AND status = 'AVAILABLE'
RETURNING seat_id;

\echo '---------- W8: double-sold invariant (SeatInvariantMonitor, every minute) ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM (SELECT 1 FROM booking_seats WHERE active GROUP BY show_id, seat_id HAVING count(*) > 1) d;

\echo '==================== candidate indexes (= V12) ===================='
CREATE INDEX ix_bookings_unpaid_expires ON bookings (expires_at, id) WHERE status IN ('HELD', 'PAYMENT_PENDING');
CREATE INDEX ix_bookings_user_created ON bookings (user_id, created_at DESC, id DESC);
DROP INDEX ix_bookings_user;
ANALYZE bookings;

\echo '==================== AFTER ===================='
\o /dev/null
SELECT id FROM bookings WHERE status IN ('HELD', 'PAYMENT_PENDING') AND expires_at <= now() ORDER BY expires_at, id LIMIT 100;
SELECT b.public_id FROM bookings b WHERE b.user_id = :heavy ORDER BY b.created_at DESC, b.id DESC LIMIT 10;
\o

\echo '---------- W1 after ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM bookings
WHERE status IN ('HELD', 'PAYMENT_PENDING') AND expires_at <= now()
ORDER BY expires_at, id
LIMIT 100
FOR UPDATE SKIP LOCKED;

\echo '---------- W2 after ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT b.public_id, b.status, b.show_id, b.total_paise, b.currency, b.expires_at, b.created_at,
       (SELECT count(*) FROM booking_seats bs WHERE bs.booking_id = b.id) AS seat_count
FROM bookings b WHERE b.user_id = :heavy
ORDER BY b.created_at DESC, b.id DESC LIMIT 10 OFFSET 0;

\echo '---------- W3 after ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT b.public_id, b.status, b.show_id, b.total_paise, b.currency, b.expires_at, b.created_at,
       (SELECT count(*) FROM booking_seats bs WHERE bs.booking_id = b.id) AS seat_count
FROM bookings b WHERE b.user_id = :heavy
ORDER BY b.created_at DESC, b.id DESC LIMIT 10 OFFSET 490;

\echo '---------- W4 after ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT b.public_id, b.status, b.show_id, b.total_paise, b.currency, b.expires_at, b.created_at,
       (SELECT count(*) FROM booking_seats bs WHERE bs.booking_id = b.id) AS seat_count
FROM bookings b WHERE b.user_id = :u0 + 1
ORDER BY b.created_at DESC, b.id DESC LIMIT 10 OFFSET 0;

\echo '---------- W5 after ----------'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM bookings WHERE user_id = :heavy;

\echo '---------- index sizes ----------'
SELECT indexrelname AS index, pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes WHERE relname = 'bookings' ORDER BY indexrelname;

ROLLBACK;
\echo 'Rolled back: no volume data or candidate index was kept.'
