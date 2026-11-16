# SeatLock: Correctness & Performance Notes

How SeatLock guarantees that nobody ever gets the same seat twice, and how its queries
perform, with evidence. Updated as each guard and optimisation lands.

## 1. Oversell guards

| Layer | Mechanism | Evidence | Status |
|---|---|---|---|
| DB | Partial unique index `ux_booking_seats_live_seat` on `booking_seats (show_id, seat_id) WHERE active`: at most one live claim per seat | `BookingPersistenceTest.databaseRejectsSecondLiveBookingForTheSameSeat`; manual SQL demo (W1·D3) | ✅ W1·D3 |
| DB | Composite FK `booking_seats (show_id, seat_id)` → `show_seats`: a claimed seat must belong to that show | Schema (`V1__init.sql`) | ✅ W1·D3 |
| DB | `UNIQUE (hold_id)` on `bookings`: a hold becomes at most one booking (idempotent confirm) | Schema (`V1__init.sql`) | ✅ W1·D3 |
| Redis | Atomic multi-seat hold in one Lua script, with TTL and `holdId` | — | ⏳ W3·D2 |
| App | 200-thread concurrency test: exactly one winner per seat | — | ⏳ W3·D6 |
| App | Optimistic locking strategy (`@Version` on `show_seats`) | — | ⏳ W4·D6 |

## 1b. Venue double-booking guard (W2·D4)

Two scheduled shows must never occupy the same venue at the same time, even if two organizers
create them at the same moment.

| Layer | Mechanism | Evidence | Status |
|---|---|---|---|
| DB | Exclusion constraint `ex_shows_venue_no_overlap` (`V6`): `EXCLUDE USING gist (venue_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&) WHERE (status = 'SCHEDULED')` via `btree_gist`. `ends_at` = start + event duration + 30 min turnover | `CreateShowIntegrationTest.twoOrganizersRacingForTheSameSlotOneWinsTheDatabaseDecides` (2 threads behind a start gate → exactly 1 created, 1 `SHOW_OVERLAP`) | ✅ W2·D4 |
| DB | `CHECK (ends_at > starts_at)`; `UNIQUE (venue_id, starts_at)` (V1) | Schema | ✅ |
| App | Constraint violation → 409 `SHOW_OVERLAP`; show + all its `show_seats` created in one transaction (no half-created show) | `overlappingShowAtTheSameVenueIs409ButBackToBackIsFine` (half-open range: back-to-back allowed) | ✅ W2·D4 |

Why not "check, then insert" in Java: two transactions can both see the venue free and both
insert. With the exclusion constraint, the second insert waits for the first transaction and
then fails, which is the same pattern as the seat guard in §1.

## 1c. Seat status from two sources (W3·D1)

The seat map reads BOOKED from Postgres and HELD from Redis hold keys. Rules:
- **Postgres wins.** A seat BOOKED in the DB is BOOKED even if a stale hold key still exists
  (`ShowCatalogServiceTest`, `SeatMapLiveStatusIntegrationTest`).
- **The key's TTL is the hold.** An expired key reads as nil, so an expired hold is AVAILABLE
  with no sweeper involved (`SeatHoldReaderIntegrationTest`).
- **Redis down = display may under-report holds, never over-sell.** The map degrades to DB statuses
  (`holdsLive = false`); correctness lives in the atomic hold script (W3·D2) and the partial unique
  index `ux_booking_seats_live_seat` (§1), not in the seat map.

## 1d. Seat holds (W3·D2, ADR-006)

| Rule | Enforced by | Proven by |
|---|---|---|
| Two users can't hold the same seat | `hold_seats.lua` (check all, then set all, atomically) | `HoldRaceIntegrationTest`: 20 threads, one seat → exactly 1 winner (×5) |
| A hold is all or nothing | Lua writes nothing when any seat is taken | overlapping [i, i+1] race: no seat held twice, no keys for unclaimed seats (×5); `RedisHoldStoreIntegrationTest` |
| One active hold per user per show | `hold:user:{u}:show:{s}` checked in the same script | one-user race: 1 hold, 19 × `HOLD_EXISTS` (×5) |
| Releasing never frees someone else's seat | `release_seats.lua` compares the value with our `holdId` | `releaseNeverDeletesASeatThatNowBelongsToAnotherHold` |
| Holds expire on their own | one TTL on every key of the hold | `anExpiredHoldDisappearsCompletely…` |
| Booked seats can't be held | DB status check before Redis | `aSeatBookedInTheDatabaseCannotBeHeld` |
| Even if Redis fails, no oversell | partial unique index `ux_booking_seats_live_seat` (§1) at booking time | W3·D4 + W3·D6 |

## 1e. Idempotent `POST /holds` (W3·D3, ADR-007)

| Rule | Enforced by | Proven by |
|---|---|---|
| A retry with the same key never creates a second hold | `UNIQUE (user_id, idem_key)` + `INSERT … ON CONFLICT DO NOTHING` claim | `aRetryWithTheSameKeyGetsTheSameHoldBack` (same `holdId`, replayed) |
| Concurrent duplicates → one hold | the same unique constraint decides the race | 20 concurrent claims → 1 winner (store); 10 concurrent HTTP duplicates → 1 hold, others replay or 409 |
| A key can't be reused for a different request | SHA-256 fingerprint of method + path + body | `theSameKeyForDifferentSeatsIs422AndHoldsNothing`, `theFingerprintCoversPathAndBody` |
| A failed attempt doesn't poison the key | release on exception (`DELETE … WHERE status = 'IN_PROGRESS'`) | `aFailedAttemptCanBeRetriedWithTheSameKey` |
| A crashed request can't block its key for 24 h | 30 s lease + compare-and-set takeover | `aStaleInProgressClaimCanBeTakenOverOnceAfterTheLease` |
| A stored response is never lost by a release | `release` only deletes `IN_PROGRESS`; `CHECK` ties COMPLETED to a response | `releaseNeverDeletesACompletedKey` |

## 1f. Booking lifecycle (W3·D4, ADR-008)

| Rule | Enforced by | Proven by |
|---|---|---|
| Only legal state changes (no double confirm, no expiring paid bookings, terminal is final) | State pattern (`BookingState`) | `BookingLifecycleTest` (all 30 status × action pairs) |
| A hold becomes at most one booking | `uq_bookings_hold_id` + re-check for the winner | 5 concurrent conversions → exactly one 201, same booking for all |
| Conversion is all or nothing | one transaction: compare-and-set `markHeld` + booking insert | seat BOOKED meanwhile → 409, other seat back to AVAILABLE |
| No oversell even if Redis lost a hold | partial unique index `ux_booking_seats_live_seat`, flushed inside the transaction | `theDatabaseIndexRejectsASeatAlreadyInALiveBooking` |
| Unpaid bookings expire and free their seats | sweeper: `FOR UPDATE SKIP LOCKED` + `expire()` + `markAvailable` | `expiresOnlyUnpaidBookingsWhoseTimeIsUpAndFreesTheirSeats`; expired seat can be sold again |
| Two sweepers never double-process | `SKIP LOCKED` batches | 2 sweepers × 30 bookings → counts add up to exactly 30 |
| Sweeper never blocks on a busy row | `SKIP LOCKED` | locked booking skipped in < 5 s, expired on the next run |
| Cancel vs sweeper can't both win | `SELECT … FOR UPDATE` on cancel; state machine | expired booking → cancel 409; cancel twice → 200 |

## 1g. Rate limits (W3·D5, ADR-009)

| Rule | Enforced by | Proven by |
|---|---|---|
| A user can't flood `POST /holds` | per-user token bucket in Redis (shared by all instances) | `HoldRateLimitIntegrationTest`: 20 pass (test profile), 21st → 429 + Retry-After |
| One user's limit never blocks another | buckets keyed by user id | same test: second user unaffected |
| Bursts allowed, sustained floods not; refill over time | Bucket4j greedy refill | `RateLimiterIntegrationTest` (burst 3, 4th rejected; refills after the period) |
| Redis down never blocks requests | fail-open in `RateLimiter` | `RateLimiterTest` |

## 2. Query plans (catalog reads)

**Method:** `docs/explain/catalog-queries.sql` on the seeded local DB (30 events, 90 shows,
1,008 seats, 22,944 `show_seats`), Postgres 16, after `VACUUM ANALYZE`, warm cache.
Raw plans are in `docs/explain/`. **Buffers** (8 KB pages touched) are the stable metric;
execution times vary run to run.

### 2.1 Baseline: V1 indexes only (W1·D5), `01-baseline.txt`

| Query | Execution time | Buffers | Key plan nodes |
|---|---|---|---|
| Q1 event list, first page | 3.31 ms | 251 | For each of 29 events: `Seq Scan on shows` (87 of 90 rows thrown away each time); `min(price)` SubPlan reads all ~257 `show_seats` rows of the show (≈7,450 rows, **190 of 251 buffers**) |
| Q2 upcoming shows of one event | 1.11 ms | 24 | `Seq Scan on shows`; `min(price)` SubPlan reads 288 rows per show (×3) |
| Q3 seat map of one show | 0.61 ms | 17 | `Bitmap Index Scan on uq_show_seats_show_seat` (288 rows) + hash join with a full scan of `seats` (1,008 rows, 10 pages) |

**Findings**
1. The "from price" (`min(price_paise)` per show) dominates Q1 and Q2: Postgres reads every
   seat row of a show to return one number. An index on `show_seats (show_id, price_paise)`
   lets it read only the first entry (index-only scan).
2. Finding each event's next show scans the whole `shows` table once per event:
   O(events × shows). Harmless at 90 shows, but it grows quadratically with the catalog.
   A partial index on `shows (event_id, starts_at) WHERE status = 'SCHEDULED'` serves both
   the filter and the `ORDER BY starts_at LIMIT 1`.
3. Q3 is already index-driven; hash-joining the small `seats` table is the right plan at
   1k seats. Revisit with volume data on W6·D5.

### 2.2 After query indexes (W1·D5, `V4`), `02-after-indexes.txt`

`V4__catalog_read_indexes.sql` added `show_seats (show_id, price_paise)` and
`shows (event_id, starts_at)` (replacing `ix_shows_event`). Same script, same data.

| Query | Time before → after | Buffers before → after | What changed in the plan |
|---|---|---|---|
| Q1 event list, first page | 3.31 → **0.66 ms** (~5×) | 251 → **121** (−52%) | `min(price)` is now `Limit → Index Only Scan using ix_show_seats_show_price`, **1 row per show** instead of ~257, `Heap Fetches: 0`; that sub-plan went from 190 to 59 buffers |
| Q2 upcoming shows of one event | 1.11 → **0.15 ms** | 24 → **10** | Same index-only `min(price)`, 1 row per show instead of 288 |
| Q3 seat map of one show | 0.61 → 0.77 ms (noise) | 17 → 16 | Unchanged shape; now reads the narrower `ix_show_seats_show_price` |

**Notes**
- The `shows (event_id, starts_at)` index is **not used yet**: with 90 rows (2 pages) a seq scan
  is genuinely cheaper, so the planner is right. It still accounts for 58 of Q1's 121 buffers
  (29 events × 2 pages). The index is there for when the catalog grows; W6·D5's volume test
  will show the crossover.
- `show_seats.status` is deliberately unindexed, so seat status updates during holds/bookings stay
  HOT (no index writes).

## 3. Locking & isolation levels

_From W3._
