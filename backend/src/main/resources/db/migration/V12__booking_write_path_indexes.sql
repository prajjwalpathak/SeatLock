-- V12: indexes for the booking write paths, chosen from plans measured at volume (W6·D5, docs/explain/write-paths.sql,
-- docs/CORRECTNESS.md section 2.3). Plain CREATE INDEX (short write lock) is fine at this size; on a large live table
-- these would be CREATE INDEX CONCURRENTLY in a separate non-transactional migration.

-- Expiry sweeper (BookingRepository.lockExpiredIds, every 30 s on both instances): only unpaid bookings, in the
-- sweeper's ORDER BY. Partial, so it stays tiny (live bookings only) while EXPIRED/CONFIRMED history grows.
CREATE INDEX ix_bookings_unpaid_expires ON bookings (expires_at, id) WHERE status IN ('HELD', 'PAYMENT_PENDING');

-- My bookings (MyBookingsQuery): WHERE user_id ORDER BY created_at DESC, id DESC LIMIT/OFFSET, without a sort.
-- Its user_id prefix also serves the count and the users FK, so it replaces ix_bookings_user.
CREATE INDEX ix_bookings_user_created ON bookings (user_id, created_at DESC, id DESC);
DROP INDEX ix_bookings_user;
