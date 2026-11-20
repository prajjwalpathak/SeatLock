# ADR-006: Seat holds in Redis (atomic Lua) backed by the DB unique index

**Date:** 2026-11-16 · **Status:** Accepted

## Context

A user picks seats, then needs a few minutes to pay. During that time nobody else may take those seats,
and if the user walks away the seats must come back on their own. Many users can click the same seat
at the same moment (flash sales), and the API runs as two instances (W5), so in-process locks are useless.

## Options considered

| Option | How | Verdict |
|---|---|---|
| **A. Redis hold via one Lua script** + DB unique index at booking time | `hold_seats.lua` checks every seat key, then sets all with one TTL, atomically | ✅ chosen |
| B. Optimistic locking on `show_seats` (`@Version`, status HELD + `held_until`) | `UPDATE … WHERE version = ?`; retry on conflict | Benchmark only (W4·D6, `SeatLockingStrategy`): every hold is a DB write + row versions; expiry needs a sweeper; contention turns into retries |
| C. `SETNX` per seat from Java | N round trips, set one by one | ❌ not all-or-nothing: a request can win seat 1 and lose seat 2, leaving half-written holds (needs compensation) |
| D. Pessimistic `SELECT … FOR UPDATE` | row locks for minutes | ❌ non-goal in the plan; locks held across user think time |

## Decision

1. **Keys** (`HoldKeys`), all with the same TTL so they expire together:
   `hold:show:{showId}:seat:{seatId} → holdId`, `hold:user:{userId}:show:{showId} → holdId`,
   `hold:id:{holdId}` → hash (userId, showId, seatIds, expiresAt).
2. **`hold_seats.lua`**: if the user already has a hold for the show → `HOLD_EXISTS`; else if any seat key
   exists → `SEATS_TAKEN` with those seats, **writing nothing**; else set every key `PX ttl`. Redis runs the
   script without interleaving, so two users can never both win a seat.
3. **`release_seats.lua`** deletes a seat key only if its value is still our `holdId` (an expired hold can't
   free a seat someone else has since taken). The same ownership check guards confirm (W3·D4).
4. **TTL 8 min, max 6 seats, one active hold per user per show** (user-approved defaults,
   `seatlock.holds.*`). Prices come from the DB at hold time.
5. **`HoldService` order:** input checks → DB checks (show bookable and not started, seats belong to the show,
   not BOOKED) → one Redis call. No DB transaction is held across the Redis call.
6. **Failure modes:** Redis down → 503 `HOLDS_UNAVAILABLE` (+ `Retry-After`), never a 500. Someone else's,
   expired or malformed `holdId` → 404 (no probing).
7. **The database stays the final guard.** When a hold becomes a booking (W3·D4), the partial unique index
   `ux_booking_seats_live_seat` rejects a second live booking of a seat even if Redis lost data or a bug
   slipped through. Redis prevents conflicts cheaply; Postgres makes oversells impossible.

## Consequences

- Holding is O(seats) in one round trip with no DB writes; expiry needs no job (TTL). A booking-side sweeper
  is still needed for DB rows that started payment (W3·D4).
- Redis has **no persistence** in prod (#64): a Redis restart drops all live holds. Users lose their
  selection, but nothing is oversold because of point 7. Acceptable for a demo; documented in FAILURES.md later.
- **W6·D3 update (F12):** the Postgres pre-check can be stale by the time the Lua claim runs (a converted hold
  drops its keys after its booking commits). `HoldService` re-checks Postgres after every successful claim and
  releases the hold if a seat stopped being AVAILABLE (409 as usual, outcome `booked_during_claim`).
- Multi-key Lua needs all keys in one slot on Redis Cluster (`hold:{show:42}:…` hash tags); single node now.
- Tested: `RedisHoldStoreIntegrationTest` (script rules), `HoldServiceTest` (validation), `HoldApiIntegrationTest`
  (HTTP), `HoldRaceIntegrationTest` (20 threads: one winner; overlapping multi-seat holds never share a seat;
  one user racing gets one hold). The 200-thread test with numbers comes on W3·D6.
