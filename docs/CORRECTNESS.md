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
