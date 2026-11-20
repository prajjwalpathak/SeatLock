# ADR-013: `SeatLockingStrategy`: Redis Lua holds (default) or optimistic Postgres claims, chosen by config

**Date:** 2026-11-19 · **Status:** Accepted

## Context

Seat holds run on Redis (ADR-006): an atomic Lua script, keys with a TTL, the DB partial unique index as the final
guard. The plan calls for a measured comparison with a database-only approach (W6·D4) instead of asserting that Redis
is faster. That needs both approaches behind one interface, with identical behaviour, proven by the same tests.

## Options considered

| Option | Verdict |
|---|---|
| **A. Strategy interface; `RedisHoldStore` + `OptimisticHoldStore` (V11 claim rows, conditional upsert); config switch** | ✅ chosen |
| B. Optimistic = `@Version` on `show_seats` with a status flip | Mixes a short-lived hold into the durable seat row (no TTL, every hold dirties the catalog table, HOT updates lost) |
| C. Pessimistic `SELECT … FOR UPDATE` on `show_seats` | Rejected in ADR-006: holds locks per request and serializes everyone on hot rows |

## Decision

1. `SeatLockingStrategy { name(); tryHold(); find(); release(); heldSeatIds() }`; `HoldService` and the seat-map
   overlay (`LiveHoldLookup`) depend only on it. `seatlock.holds.strategy = redis-hold` (default) | `optimistic`,
   logged at startup.
2. **Optimistic store (V11):** `seat_holds` (one row per hold) and `seat_hold_claims` (one row per show seat, reused).
   One transaction: advisory lock per (user, show) → existing live hold? `HOLD_EXISTS` → insert the hold → one
   multi-row `INSERT … ON CONFLICT (show_id, seat_id) DO UPDATE … WHERE expires_at <= now RETURNING seat_id`, seats in
   sorted order → fewer rows returned than asked = roll back and report the taken seats. No lock outlives the request;
   a concurrent claim waits for the first commit, then sees a live claim. `version` counts hand-overs.
3. Same contract for both: all or nothing, one hold per user and show, expiry at `expiresAt` (app clock), release only by
   the owning hold, `DataAccessException` → 503. `show_seats` stays AVAILABLE while held in both, so hold → booking is
   unchanged. The `booking_seats` partial unique index remains the oversell guarantee either way.
4. Proven by running races A and B (200 users) against both (`*OptimisticIntegrationTest` subclasses with a property
   override) and `OptimisticHoldStoreIntegrationTest` (20 concurrent claims → 1 winner, expiry hand-over, release).

## Consequences

- **Measured (W6·D4, `docs/benchmarks.md` section 5):** redis-hold 336 vs optimistic 233 holds/s, p99 188 vs 310 ms;
  flash-sale p99 0.97 vs 1.99 s; optimistic exhausted the DB pool on a cold rush (503s). `redis-hold` stays the default.

- Prod stays on `redis-hold`. `optimistic` removes Redis from the hold path (still used for caching and rate limits).
- First, informal numbers from the race logs (laptop, same run): race A p50 ~0.9 s (redis-hold) vs ~1.3 s (optimistic);
  the proper benchmark (throughput, conflicts, p99, both strategies, same load) is W6·D4.
- Expired optimistic holds are deleted hourly (claims cascade); expired claim rows are simply taken over before that.
