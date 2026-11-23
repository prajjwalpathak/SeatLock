# SeatLock: Correctness & Performance Notes

How SeatLock guarantees that nobody ever gets the same seat twice, and how its queries
perform, with evidence. Updated as each guard and optimisation lands.

## 1. Oversell guards

| Layer | Mechanism | Evidence | Status |
|---|---|---|---|
| DB | Partial unique index `ux_booking_seats_live_seat` on `booking_seats (show_id, seat_id) WHERE active`: at most one live claim per seat | `BookingPersistenceTest.databaseRejectsSecondLiveBookingForTheSameSeat`; manual SQL demo (W1·D3) | ✅ W1·D3 |
| DB | Composite FK `booking_seats (show_id, seat_id)` → `show_seats`: a claimed seat must belong to that show | Schema (`V1__init.sql`) | ✅ W1·D3 |
| DB | `UNIQUE (hold_id)` on `bookings`: a hold becomes at most one booking (idempotent confirm) | Schema (`V1__init.sql`) | ✅ W1·D3 |
| Redis | Atomic multi-seat hold in one Lua script, with TTL and `holdId` | `RedisHoldStoreIntegrationTest`, `HoldRaceIntegrationTest` (section 1d) | ✅ W3·D2 |
| App | 200-thread concurrency tests: exactly one winner per seat, 0 double-sold, DB index holds alone | `HoldRace200IntegrationTest`, `OverlappingHoldsRaceIntegrationTest`, `LiveSeatIndexRaceIntegrationTest` (section 1h) | ✅ W3·D6 |
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
then fails, which is the same pattern as the seat guard in section 1.

## 1c. Seat status from two sources (W3·D1)

The seat map reads BOOKED from Postgres and HELD from Redis hold keys. Rules:
- **Postgres wins.** A seat BOOKED in the DB is BOOKED even if a stale hold key still exists
  (`ShowCatalogServiceTest`, `SeatMapLiveStatusIntegrationTest`).
- **The key's TTL is the hold.** An expired key reads as nil, so an expired hold is AVAILABLE
  with no sweeper involved (`SeatHoldReaderIntegrationTest`).
- **Redis down = display may under-report holds, never over-sell.** The map degrades to DB statuses
  (`holdsLive = false`); correctness lives in the atomic hold script (W3·D2) and the partial unique
  index `ux_booking_seats_live_seat` (section 1), not in the seat map.

## 1d. Seat holds (W3·D2, ADR-006)

| Rule | Enforced by | Proven by |
|---|---|---|
| Two users can't hold the same seat | `hold_seats.lua` (check all, then set all, atomically) | `HoldRaceIntegrationTest`: 20 threads, one seat → exactly 1 winner (×5) |
| A hold is all or nothing | Lua writes nothing when any seat is taken | overlapping [i, i+1] race: no seat held twice, no keys for unclaimed seats (×5); `RedisHoldStoreIntegrationTest` |
| One active hold per user per show | `hold:user:{u}:show:{s}` checked in the same script | one-user race: 1 hold, 19 × `HOLD_EXISTS` (×5) |
| Releasing never frees someone else's seat | `release_seats.lua` compares the value with our `holdId` | `releaseNeverDeletesASeatThatNowBelongsToAnotherHold` |
| Holds expire on their own | one TTL on every key of the hold | `anExpiredHoldDisappearsCompletely…` |
| Booked seats can't be held | DB status check before Redis | `aSeatBookedInTheDatabaseCannotBeHeld` |
| Even if Redis fails, no oversell | partial unique index `ux_booking_seats_live_seat` (section 1) at booking time | W3·D4 + W3·D6 |

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
| Redis down never blocks requests; a stale connection heals itself | one retry on a freshly built proxy, then fail-open (`RateLimiter`) | `RateLimiterTest` |

## 1h. Concurrency proof: 200 at once (W3·D6)

**Method.** `ConcurrencyHarness` (test support) gives each request its own platform thread; all threads wait
on a start gate (`CountDownLatch`) and are released together. Requests go over **real HTTP to a real Tomcat
port** (`@RealServerTest`, `RANDOM_PORT`): sockets, worker pool, JWT filter, rate limiter, idempotency,
DB checks, Lua. Every outcome is kept; a request that gets no HTTP response counts as a failure
(`transportErrors` must be empty). `peakInFlight` (most requests open at once) proves real overlap.
Each user is distinct with a real JWT and a fresh `Idempotency-Key`, so only the hold logic decides.

| Race | Setup | Must hold | Result (2026-11-17/18, laptop, Testcontainers) |
|---|---|---|---|
| **A** one seat | 200 users hold the same seat (×3, after one unasserted warm-up) | exactly 1 × 201, 199 × 409 `SEATS_UNAVAILABLE`; Redis seat key = winner's holdId; exactly 1 hold record | ✅ 3/3: `{201=1, 409=199}`, peakInFlight 134–200 |
| **B** overlapping sets | 200 users hold random 1–4 seats of one 12-seat block; then all winners convert to bookings at once (×3, seed logged) | only 201/409; no seat in two holds; Redis = API exactly; every winner's booking 201; **double-sold query → 0 rows**; `booking_seats` = `show_seats` HELD = winners' seats; one booking per winner, none for losers | ✅ 3/3: 7–8 winners, **12/12 seats sold, 0 double-sold**, peakInFlight 200 |
| **C** DB alone | Redis, Lua and the `show_seats` CAS bypassed: 200 transactions (pool of 50) insert a live `booking_seats` row for one seat, each pausing 20 ms before COMMIT | exactly 1 COMMIT; 199 × `23505` on `ux_booking_seats_live_seat`; losers' whole transactions (booking row too) rolled back | ✅ `{COMMITTED=1, 23505 ux_booking_seats_live_seat=199}`, 248 ms wall |

The invariant query (`OverlappingHoldsRaceIntegrationTest.DOUBLE_SOLD_SEATS`):

```sql
SELECT show_id, seat_id, count(*) AS live_claims FROM booking_seats
WHERE active GROUP BY show_id, seat_id HAVING count(*) > 1;   -- must return 0 rows, always
```

**What race C shows about Postgres.** With a unique index, a second inserter of the same key does not fail
immediately while the first is uncommitted: it **waits** on the first transaction, then raises 23505 when that
one commits (it would succeed if the first rolled back). So the index is a correct last line of defence under
any interleaving, at the cost of brief blocking, which is why Redis turns away almost all contenders first.

**Latency under contention** (race A, 200 at once on one seat, warm): p50 0.7–1.2 s, p99 1.8–2.3 s;
the first race on a cold server took p50 ~6–7 s. This is a correctness test on a laptop running two sets of
containers, not a benchmark; throughput is measured with k6 later (W6). Uncontended baseline for the same
harness (`GET /system/time` ×200): p50 12 ms, p99 160 ms.

**Problems found while building it** (test setup, not product bugs; the race logic never produced a second
winner):
- 3 of 200 requests got no response on the cold first run: a burst of 200 connects overflowed Tomcat's TCP
  backlog (`accept-count`, default 100) and Windows reset the extras → `server.tomcat.accept-count=512` for
  the race context only (production sits behind Nginx) + one warm-up race.
- `peakInFlight > 100` was too strict: on a warm server early requests finish before the last threads wake
  (53–200 seen) → assert ≥ 10% overlap; correctness asserts unchanged.

## 1i. Payment orders (W4·D1, ADR-010)

| Rule | Enforced by | Proven by |
|---|---|---|
| At most one open or successful payment per booking | partial unique index `ux_payments_live_booking`; booking row lock (`startPayment`) serializes attempts | `PaymentPersistenceTest`; `PaymentOrderRaceIntegrationTest`: 20 concurrent "Pay now" → 1 × 201, 19 × 200, same order, 1 row (×5) |
| A failed attempt can be retried | FAILED rows drop out of the partial index; `attempt` = last + 1 (`UNIQUE (booking_id, attempt)`) | `PaymentPersistenceTest`, `PaymentOrderIntegrationTest.aNewAttemptIsAllowedAfterAFailedOne` |
| The client can't choose the amount | no request body; `BookingPricing` = sum of live seat prices, must equal `total_paise`; gateway echo checked | `BookingPricingTest`, `PaymentServiceTest`, `PaymentOrderIntegrationTest.aTamperedTotalIsNeverCharged` |
| Expired, cancelled or paid bookings can't be paid | `checkPayable` / `startPayment` (state machine + deadline on the app clock), repeated under the lock | `PaymentOrderIntegrationTest` (409 `BOOKING_EXPIRED` even before the sweeper, 409 `INVALID_BOOKING_TRANSITION`) |
| A gateway order id is used once | `UNIQUE (gateway, gateway_order_id)` | `PaymentPersistenceTest` |
| A slow or failing Razorpay never hangs a request or leaks the secret | `RestClient` 2 s connect / 5 s read; every failure → 503 `PAYMENTS_UNAVAILABLE`; only the error code is logged | `RazorpayGatewayTest` (401 without the secret in the message, 5xx, timeout) |
| A Razorpay response for another amount (or without an order id) is never stored | adapter checks id prefix, amount, currency against the request | `RazorpayGatewayTest` |
| Live keys can't take real money | startup refuses non-`rzp_test_` keys | `RazorpayGatewayTest.configNeedsTestModeKeys` |
| No DB connection or lock is held during the provider call | gateway called before the transaction opens | `PaymentServiceTest` (gateway failure: no transaction, nothing stored) |

## 1j. Payment confirmation (W4·D3, ADR-011)

| Rule | Enforced by | Proven by |
|---|---|---|
| Nothing unsigned changes state | checkout HMAC(key_secret, order\|payment), webhook HMAC(webhook_secret, raw body), constant-time compare, blank secret never valid | `RazorpaySignaturesTest` (independent vectors, re-serialized body fails), `PaymentVerifyIntegrationTest`, `RazorpayWebhookIntegrationTest` (400, nothing stored) |
| A payment confirms its booking exactly once | payment row lock → booking row lock in one transaction; CREATED → CAPTURED guard; `ALREADY_DONE` for the same payment id | `PaymentConfirmRaceIntegrationTest`: 10 callbacks + 10 webhooks at once → 1 CONFIRMED, 19 ALREADY_DONE (×5) |
| Booking, seats and payment always agree | CONFIRMED only with all seats HELD → BOOKED (count checked, else rollback); sweeper and capture serialize on the booking row | same test with the sweeper looping: CONFIRMED + BOOKED, or EXPIRED + AVAILABLE + payment CAPTURED (×5) |
| A redelivered webhook does nothing | `webhook_events (provider, event_id)` UNIQUE, claimed in the processing transaction | `RazorpayWebhookIntegrationTest` (same id twice, no id → body hash) |
| A failed processing is retried, not lost | claim rolls back with the processing (500 → Razorpay retries) | design (claim + capture share one transaction) |
| Partial or mismatched payments never confirm | reported amount must equal the order's | `PaymentCaptureIntegrationTest`, `RazorpayWebhookIntegrationTest` (NEEDS_REVIEW) |
| Money taken after expiry is never lost | payment stays CAPTURED, outcome `CAPTURED_BOOKING_LOST` | `PaymentCaptureIntegrationTest` (refund: W4·D4) |
| Someone else's order can't be confirmed via my session | verify checks the booking owner (404) | `PaymentVerifyIntegrationTest` |

## 1k. Refunds (W4·D4, ADR-012)

| Rule | Enforced by | Proven by |
|---|---|---|
| A refund that is owed is never forgotten | outbox row written in the deciding transaction (capture / cancel) | `RefundProcessorIntegrationTest`, `PaidCancellationIntegrationTest` |
| A refund is never queued twice | `UNIQUE (gateway_payment_id, reason)` + payment row lock; `request()` returns the existing row | late payment redelivered → 1 row; 6 concurrent cancels → 1 row |
| A refund is never sent twice | claim with SKIP LOCKED + lease; provider lookup by receipt before creating | `RazorpayGatewayTest` (existing receipt → no POST), `RefundProcessorIntegrationTest` (second claim empty) |
| A provider failure is retried, then escalated | backoff 30 s · 2ⁿ ≤ 1 h, FAILED after 8 with ERROR log | `RefundTest`, `RefundProcessorTest` |
| A failed Checkout try never strands a booking | `payment.failed` keeps the order CREATED | `RazorpayWebhookIntegrationTest` (failed, then captured → CONFIRMED) |
| Cancellation refunds follow the policy, never more | `RefundPolicyEngine` (tiers, inclusive bounds, floor) | `RefundPolicyEngineTest` |
| Cancelled seats go back on sale at once, history kept | booking REFUNDED (booking_seats inactive) + BOOKED → AVAILABLE in one tx | `PaidCancellationIntegrationTest` (another user holds the seat) |

## 1l. Payments over real HTTP and real webhook behaviour (W4·D5)

| Rule | Enforced by | Proven by |
|---|---|---|
| A slow Razorpay can't hold a request | read timeout (5 s prod; 800 ms in the test) on a real socket | `RazorpayWireMockTest` (3 s delay → fails in < 2.5 s) |
| Connection resets, empty or malformed responses never store anything | every I/O / parse failure → `PaymentGatewayException` → 503 | `RazorpayWireMockTest`, `RazorpayFlowIntegrationTest` (503, no payment row, booking still HELD) |
| The real wiring (config → adapter → outbox) works | full app with `gateway=razorpay` against WireMock: order, webhook confirm, cancel, Refund API | `RazorpayFlowIntegrationTest` |
| HTTP/2 quirks can't break provider calls | Razorpay client pinned to HTTP/1.1 | WireMock tests failed on h2c (RST_STREAM) before the pin |
| Events in any order confirm once | shared capture path, payment row lock | `RazorpayWebhookScenariosIntegrationTest` (order.paid before payment.captured) |
| A late failure report never undoes a payment | `recordFailedAttempt` is a no-op once paid | same (payment.failed after capture) |
| 10 identical deliveries at once → processed once | `ON CONFLICT DO NOTHING` waits for the first claim's commit | same (1 row, CONFIRMED) |
| A delivery we failed (500) is processed on Razorpay's retry | claim and processing roll back together | same (500 → 0 rows, CREATED; retry → CONFIRMED) |
| Confirm vs sweeper is consistent whichever wins | locks (ADR-011) | `PaymentConfirmRaceIntegrationTest`: sweeper started at once or 60 ms late, both branches logged |

## 1m. Seat locking strategies (W4·D6, ADR-013)

| Rule | Enforced by | Proven by |
|---|---|---|
| Both strategies give the same guarantees | one `SeatLockingStrategy` contract; `HoldService` unchanged | races A/B run on both (`*OptimisticIntegrationTest`): 1 winner of 200; overlapping holds never share a seat; 0 double-sold |
| Optimistic claims never take a live seat | `ON CONFLICT … DO UPDATE … WHERE expires_at <= now` | `OptimisticHoldStoreIntegrationTest` (20 concurrent → 1 winner) |
| Optimistic holds are all or nothing | fewer rows returned than asked → rollback + taken seats reported | same (overlap: loser writes nothing) |
| One live hold per user and show (optimistic) | transaction-scoped advisory lock + live-hold check | same |
| No deadlocks between overlapping optimistic holds | seats claimed in sorted order in one statement | race B on optimistic (×3) |
| The DB stays the last line of defence | `ux_booking_seats_live_seat` under both strategies | race C (unchanged), race B bookings on both |

## 1n. Live seat events (W5·D1, ADR-014)

| Guarantee | How | Proof |
|---|---|---|
| A rolled-back change is never announced | `@TransactionalEventListener(AFTER_COMMIT)` | `SeatEventsIntegrationTest` (rollback → no message) |
| Every seat change is announced, with exactly the seats that moved | events from the two choke points; `UPDATE … RETURNING seat_id` | same test; no event when 0 rows change |
| A converted hold doesn't flip seats back to AVAILABLE | `releaseConvertedHold` publishes nothing | `HoldServiceTest` |
| Missed messages are detectable | per-show Redis `INCR` version, consecutive per show | consecutive versions asserted |
| Clients can't inject seat events | SEND refused before the broker | `StompSecurityIntegrationTest` |
| Same events, same order on every instance | version + PUBLISH in one Lua script; one forwarding thread; broker preserves publish order | `SeatEventRelayIntegrationTest` (2 instances × 20 concurrent changes → identical, consecutive streams) |
| A resync never loses an update | seat map's `version` read before its statuses; apply only newer messages | `ShowCatalogServiceTest` (read order), `SeatEventsIntegrationTest` (map version = last event) |
| Lost Pub/Sub messages are detected | at-most-once delivery shows up as a version gap → reload the map | `applySeatEvent` (gap → resync); manual two-browser check W5·D3 |
| Nothing missed between subscribing and loading | every (re)subscription reloads the map; older events ignored by version | `useLiveSeatMap` |
| A Redis restart (versions start at 1 again) doesn't freeze the map | smaller version than seen, or an event ≤ map.version that happened after the load → reload | `applySeatEvent` |

**Known window:** versions are assigned after commit. If two transactions on the same show commit back to back,
their after-commit hooks can run in the opposite order, so the later state could carry the smaller version.
Each message is still a true state at its commit; the client's resync (W5·D2/D3) repairs the view. A
commit-ordered version (column on `shows`) was rejected: it would serialize every hold/booking of a show on one row.

## 1o. Under load: k6 flash sales (W6·D3)

| Check | How | Result |
|---|---|---|
| No sale seat won twice | k6 threshold `seat_wins{slot:N} <= 1` for every seat of the block | ✅ final code (one violation before the F12 fix, absorbed by the DB guard) |
| Seats won ≤ seats on sale | k6 threshold `seats_won <= SEATS` | ✅ |
| No seat with two live bookings | `run-k6.ps1` → invariant SQL on Postgres after each run | ✅ **0 in all 15 sales** (300 → 20 and 500 → 10 seats) |
| Hold granted on a just-booked seat | post-claim re-check in `HoldService` (F12) | ✅ released + 409 (`booked_during_claim`) |

Method and numbers: `docs/benchmarks.md` section 4; failures F11/F12 in `docs/FAILURES.md`.

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

### 2.3 Booking write paths at volume (W6·D5, `V12`), `03-write-paths.txt`

`docs/explain/write-paths.sql`: one transaction, **rolled back**, adds 205,303 bookings (0.2 % unpaid and due, 0.8 %
unpaid, 12 % confirmed, 4 % cancelled, rest expired; one user with 5,000) and as many `booking_seats`, then plans the
queries before and after the candidate indexes. Warm cache, `max_parallel_workers_per_gather = 0`.

| Query | Before | After | Buffers | Index |
|---|---|---|---|---|
| W1 expiry sweeper (`lockExpiredIds`, 100 rows, `FOR UPDATE SKIP LOCKED`) | 34.47 ms: Seq Scan, 204,893 rows filtered, sort | **0.14 ms**: Index Scan, no sort | 3,913 → 202 | `ix_bookings_unpaid_expires (expires_at, id) WHERE status IN (HELD, PAYMENT_PENDING)`: **80 kB** |
| W2 My bookings page 1, 5,000-booking user | 5.18 ms: Bitmap scan of 5,000 rows + top-N sort | **0.08 ms**: Index Scan, 10 rows | 881 → 46 | `ix_bookings_user_created (user_id, created_at DESC, id DESC)`: 8.1 MB |
| W3 same, page 50 (`OFFSET 490`) | 4.55 ms | 1.52 ms | | offset still walks 500 entries (offset paging by design, #170) |
| W4 My bookings page 1, ~100-booking user | 0.26 ms | 0.16 ms | | |
| W5 `count(*)` for the 5,000-booking user | 2.07 ms | 2.67 ms (no gain) | 840 → 863 | rows inserted in this transaction aren't all-visible, so no index-only count here; after `VACUUM` it can be |
| W6 seats by id (hold pre/post check) | 0.08 ms | (unchanged) | | `uq_show_seats_show_seat` |
| W7 compare-and-set `UPDATE show_seats` | 0.83 ms | (unchanged) | | same |
| W8 double-sold invariant | 0.04 ms | (unchanged) | | partial unique index (live rows only) |

- **Sweeper:** the biggest win. Its cost no longer grows with booking history: the partial index holds only unpaid
  rows (80 kB for ~2,050 of 205k bookings), and `LIMIT 100` stops after 100 index entries. A booking leaves the index when it
  becomes CONFIRMED / EXPIRED / CANCELLED (that status write is no longer a HOT update; bookings change a few times in
  their life, unlike `show_seats`, which stays unindexed on `status`, #29).
- **My bookings:** the index delivers rows in the page's order, so page 1 reads 10 rows instead of all of the user's.
  It replaces `ix_bookings_user` (its `user_id` prefix serves the count and the users FK). Deep pages still pay for the
  offset; keyset paging would fix that if users ever have thousands of bookings (not needed now).
- Not indexed: `refunds` (partial `ix_refunds_due` since W4·D4), `webhook_events` cleanup (daily, 30-day table),
  `idempotency_keys` (indexed since W3·D3).

### 2.4 Policy vector search (W7·D4, `V14`)

`ORDER BY embedding <=> :q LIMIT 4` over `policy_chunks` (40 rows, 384-dim vectors). At this size Postgres
reads the table sequentially (it fits in a couple of pages, cheaper than the index); the HNSW index
`ix_policy_chunks_embedding (vector_cosine_ops)` is there for growth. To see it used:
`SET enable_seqscan = off; EXPLAIN ANALYZE SELECT ... ORDER BY embedding <=> '[...]'::vector LIMIT 4;`
→ `Index Scan using ix_policy_chunks_embedding`. HNSW is approximate: with 40 rows the default
`hnsw.ef_search = 40` already visits every vector, so results equal exact search; recall is measured end to end
by `load-tests/rag-recall-eval.ps1`.

Ingestion safety: content hash + model name per document (unchanged files skipped), embedding outside any
transaction, then one transaction under `pg_advisory_xact_lock(hashtext('policy:' || doc))` that re-checks the
hash before replacing the chunks. Two instances starting together write each document once
(`PolicyIngestionIntegrationTest`, 4 concurrent writers).

## 3. Locking & isolation levels (W6·D5)

Every transaction runs at Postgres' default **READ COMMITTED** (pinned by `IsolationIntegrationTest`). No guard relies
on a snapshot: each one is either a **constraint** (checked against all committed and in-flight rows, whatever the
isolation level) or a **row lock / conditional write** whose behaviour under READ COMMITTED is exactly what we want.

| Guard | Mechanism | Why READ COMMITTED is enough |
|---|---|---|
| One live claim per seat | partial unique index `ux_booking_seats_live_seat` | Uniqueness is checked in the index, not the snapshot: a second insert waits for the first transaction, then fails 23505 (or succeeds if it rolled back). Race C: 1 commit, 199 × 23505 |
| Seat status AVAILABLE → HELD → BOOKED | compare-and-set `UPDATE … WHERE status = 'AVAILABLE'` | A writer that waits on a locked row **re-evaluates its WHERE against the newly committed row** (EvalPlanQual) and updates 0 rows instead of overwriting. Test: second CAS waits, then 0 rows |
| Venue double-booking | exclusion constraint `ex_shows_venue_no_overlap` | Like unique: enforced in the GiST index across transactions |
| Booking lifecycle (sweeper vs cancel vs payment confirm) | `SELECT … FOR UPDATE` on the booking row; lock order payment → booking | Whoever locks second waits and then reads the committed state (state machine refuses invalid moves) |
| Sweeper / refund worker on 2 instances | `FOR UPDATE SKIP LOCKED` | Locked rows are skipped immediately, never processed twice. Test: locked row skipped, < 1 s |
| Idempotency keys, webhook dedupe | `INSERT … ON CONFLICT DO NOTHING` on a unique key | The conflict check is index-level; the loser sees "0 rows" at once |
| One optimistic hold per user and show | `pg_advisory_xact_lock(user, show)` + conditional upsert | Serializes the user's own attempts explicitly |

**Why not REPEATABLE READ or SERIALIZABLE.** The same compare-and-set at REPEATABLE READ fails with **40001**
(`could not serialize access due to concurrent update`) when the row changed after the snapshot (pinned by
`IsolationIntegrationTest`). Every contended write path would then need a retry loop, and a flash sale is exactly the
case where hundreds of transactions touch the same rows: retries multiply load at the worst moment. SERIALIZABLE adds
predicate-lock tracking and more 40001s for no extra safety, because our invariants are already enforced by
constraints and explicit row locks.

**Write skew check.** The classic READ COMMITTED gap is "two transactions read, both decide, both write different
rows". Where that pattern exists we made the decision a single row or a constraint: seat claims (unique index),
venue overlap (exclusion constraint), one live payment per booking (partial unique `ux_payments_live_booking`), one
hold per user/show (Lua user key, or the advisory lock), payment creation (serialized on the booking row lock).
Reads that are allowed to be slightly stale are pre-checks only, and are re-validated where it matters (F12:
re-check after the claim).

