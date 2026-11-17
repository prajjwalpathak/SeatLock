# SeatLock: Benchmarks

Every number here comes from a script in `load-tests/` that anyone can re-run. Machine and
setup are recorded with each table, because numbers without context are meaningless.

## 1. Catalog reads: no cache vs Redis cache (W2·D4)

**What:** per-request latency of the three public catalog reads, one client, sequential,
500 timed requests after 200 warm-up requests (`load-tests/bench-catalog.ps1`).
**Setup:** local Docker stack (Postgres 16 + Redis 7, WSL 2), seeded data (30 events, 90 shows,
~23k show_seats), backend on the `local` profile with `LOGGING_LEVEL_COM_SEATLOCK=INFO`.
**Machine:** Intel i7-8550U @ 1.8 GHz (4C/8T, laptop), 16 GB DDR4-2400, Windows 11.

**Three variants**, so every change is measured against what it replaced:

| Variant | Code | Seat map reads |
|---|---|---|
| **V0 original** | commit `e7dd410` (W2·D3, before any caching) | 1 indexed join (header + seats) |
| **V1 split, cache off** | W2·D4 code, `SEATLOCK_CACHE_ENABLED=false` | 4 queries: header, layout, prices, taken seats |
| **V2 cache on** | W2·D4 code, cache enabled | 2 queries (header, taken seats) + 2 Redis reads |

### Run 2 (2026-11-15, valid)

Each variant: one app start, the script run **3 times back to back**; the table shows the
**median of the 3 runs** (range in brackets). Variants were run one after another in one
session, plugged in, nothing else running.

| Endpoint | V0 original p50 / p95 / p99 | V1 split, no cache p50 / p95 / p99 | V2 cache on p50 / p95 / p99 | **V2 vs V0** |
|---|---|---|---|---|
| `GET /api/v1/events` | 5.35 / 6.46 / 8.34 | 5.96 / 7.31 / 9.84 | **4.16 / 5.12 / 6.14** | p50 **−22%**, p95 **−21%**, p99 **−26%** |
| `GET /api/v1/events/{id}` | 4.46 / 5.33 / 6.52 | 5.38 / 6.95 / 10.20 | **2.77 / 3.61 / 4.39** | p50 **−38%**, p95 **−32%**, p99 **−33%** |
| `GET /api/v1/shows/{id}/seats` | **6.62 / 7.72 / 9.83** | 11.54 / 16.24 / 21.92 | 9.79 / 12.29 / 14.65 | p50 **+48%**, p95 **+59%** (worse) |

Ranges (min–max of the 3 runs, p95): list V0 6.18–6.93, V2 4.98–6.06 · detail V0 5.14–6.39,
V2 2.93–3.70 · seat map V0 6.95–7.79, V2 12.10–13.02. The ranges don't overlap, so the
differences are larger than the run-to-run noise.

**Cache counters after the 3 V2 runs** (proof the cache was used; not a production hit rate):
eventSearch 2101 hits / 2 misses · eventDetail 2102 / 1 · venueLayout 2100 / 0 · showPrices
2100 / 0 (= 3 runs × 700 requests; venueLayout/showPrices were already in Redis from earlier).

**Conclusions**
- **Event detail cache: keep.** −38% p50, −32% p95 against the original code.
- **Event list cache: keep.** −21–26% across percentiles against the original. (Run 1 showed
  no gain because every cache hit still opened a DB transaction and DEBUG logging added noise.)
- **Seat-map split + layout/price caches: remove.** The original single indexed join (V0) is
  clearly the fastest: decoding the cached 252-cell layout and price map from Redis (V2) costs
  more than the one join it replaces, and the split without cache (V1) is worse still. Live
  seat status (W3·D1) will be revisited with its own measurements.
- Session-to-session variation on this laptop is large (V0 seat map p50 was 12.36 ms in a single
  earlier run vs 6.62 ms here), so only variants measured in the same session are compared.

### Run 1 (2026-11-14, superseded): kept for the record

| Endpoint | p50 off → on | p95 off → on | p99 off → on |
|---|---|---|---|
| `GET /api/v1/events/{id}` | 6.09 → 4.71 | 8.76 → 5.53 | 10.71 → 6.73 |
| `GET /api/v1/events` | 6.31 → 6.34 | 8.80 → 8.78 | 11.47 → 11.01 |
| `GET /api/v1/shows/{id}/seats` | 13.89 → 17.00 | 16.89 → 21.44 | 19.83 → 24.95 |

Hits/misses: eventDetail 550/1, eventSearch 549/2, venueLayout 549/1, showPrices 549/1 (all
consistent with the request counts, so statistics work).

**Why it was superseded (found in review):**
1. "Cache off" for the seat map was the new 4-query split path, not the original single join, so
   it said nothing about the change versus the original code. → added V0.
2. Both runs logged every request at DEBUG to the Windows console (noise in both). → INFO.
3. Cache hits still ran inside a `@Transactional` service method (connection borrowed, read-only
   transaction begun and committed) → fixed: cache advice now runs before the transaction advice
   and the read services are not transactional; a test asserts a hit borrows no connection.
4. 50 warm-up requests was on the low side for the JIT. → 200.

## 2. Seat map with the live hold overlay (W3·D1)

**What:** `GET /api/v1/shows/34/seats` (252 seats) after adding the Redis hold overlay (one `MGET` of
every AVAILABLE seat's hold key, merged into the DB statuses). Same script and method as §1
(`load-tests/bench-catalog.ps1 -Only seats -ShowId 34`, 200 warm-up + 500 timed requests,
`LOGGING_LEVEL_COM_SEATLOCK=INFO`, median of the runs). Fake holds written with `load-tests/seed-holds.ps1`.
**Machine:** same laptop as §1.

| Variant | Code | Redis |
|---|---|---|
| **B0 baseline** | `main` at W2·D6 (git worktree): one join, inside a read-only `@Transactional` | ignored |
| **B1 overlay, 0 holds** | `w3-d1`: one join + one `MGET`, no transaction | up, no hold keys |
| **B2 overlay, 50 holds** | same | 50 of 252 seats held |
| **B3 Redis down** | same, `REJECT_COMMANDS` fail-fast | container stopped |

### Results (2026-11-16): two sessions, all variants in each

p50 / p95 / p99 in ms, median of the runs in that session (number of runs in brackets).

| Variant | Session 1 | Session 2 |
|---|---|---|
| B0 baseline | 6.00 / 6.87 / 7.65 (3) | 6.25 / 9.62 / 12.57 (3, one noisy run: p95 14.17) |
| B1 overlay, 0 holds | **5.41 / 6.36 / 7.14** (5) | **5.30 / 6.19 / 7.32** (3) |
| B2 overlay, 50 holds | 5.41 / 6.30 / 7.09 (3) | 5.35 / 6.32 / 7.91 (3) |
| B3 Redis down | 5.48 / 6.52 / 7.90 (3) | 4.51 / 5.27 / 6.01 (3) |

**Conclusions**
- **No measurable cost from the overlay.** B1 and B2 are *faster* than the baseline in both sessions
  (p50 −10% / −15%). The new code does more work (an extra Redis round trip) but dropped the read-only
  transaction around the seat-map reads (moved out so no DB connection is held while waiting on
  Redis). The likely explanation is that the saved transaction overhead (begin/commit, Hibernate
  session) outweighs the `MGET`. This was **not isolated** with its own variant, so the claim stays
  "no regression", not "faster".
- **Holds don't change the cost.** B2 ≈ B1: rebuilding 50 `SeatView`s is negligible next to the I/O.
- **Cost of the `MGET` itself:** between ~0 ms (session 1, B3 ≈ B1) and ~0.8 ms p50 (session 2,
  B3 vs B1): below the session-to-session noise on this laptop.
- **Redis down is fast.** B3 serves the DB view in ~5 ms. Before the `REJECT_COMMANDS` fix, the same
  drill took **0.51 s per request** (every call waited the 500 ms command timeout); see `FAILURES.md`.
- Seat map stays uncached (ADR-004): a live overlay can't be cached, and the uncached path is ~5–6 ms.

## 3. Hold contention: 200 users at once (W3·D6)

From the concurrency tests (`docs/CORRECTNESS.md` §1h). Real HTTP to a real Tomcat port, Testcontainers
Postgres + Redis, Windows laptop. Correctness runs, not load tests: numbers are indicative only.

| Scenario | Outcome | p50 | p95 | p99 | Wall |
|---|---|---|---|---|---|
| Harness baseline: `GET /system/time` ×200 | 200 × 200 | 12 ms | 147 ms | 160 ms | 164 ms |
| Race A cold (warm-up, not asserted) | 1 × 201, 199 × 409 | 7.2 s | 8.3 s | 8.4 s | 8.4 s |
| Race A warm, run 1 / 2 / 3 | 1 × 201, 199 × 409 | 1.19 / 0.72 / 1.01 s | 2.18 / 1.63 / 1.79 s | 2.33 / 1.79 / 1.88 s | 1.9–2.3 s |
| Race B holds (12-seat block), run 2 / 3 | 8 × 201, 192 × 409 | 2.5 / 2.1 s | 3.7 / 3.4 s | 3.8 / 3.5 s | 3.5–3.8 s |
| Race B bookings (8 winners at once) | 8 × 201 | 56–63 ms | 79–86 ms | 79–86 ms | 79–86 ms |
| Race C: 200 direct DB transactions, one seat | 1 COMMIT, 199 × 23505 | — | — | — | 248 ms |

Race B run 1 (cold context, no warm-up) took p50 9.4 s for holds and 530 ms for bookings; excluded above.

**Takeaways**
- Contention costs latency, never correctness: every run had exactly the right number of winners.
- The cold first race is ~6× slower (connection pools, JIT, Lua `SCRIPT LOAD`): production warm-up matters.
- Under a 200-request burst, each hold waits on DB connections (pool of 10) and Tomcat threads; that's queuing,
  not lock contention (Redis decides in microseconds). Pool sizing is revisited with k6 in W6.

