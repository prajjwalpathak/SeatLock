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
