# ADR-004: Redis caching for catalog reads, kept only where measured to help

**Date:** 2026-11-20 (W2·D4) · **Status:** Accepted · Evidence: `docs/benchmarks.md` section 1

## Context
Catalog reads (event list, event detail, seat map) are the busiest public endpoints and the
same for every user. Redis is already in the stack. A cache adds staleness, eviction work and
failure modes, so it has to pay for itself in measured latency.

## Decision
1. **Spring Cache (cache-aside) on Redis** with our own `RedisCacheManager`:
   - keys `seatlock:cache:<name>::<key>`; per-cache TTL; no null values; unknown cache names
     rejected (`disableCreateOnMissingCache`);
   - **one typed JSON serializer per cache** (the type comes from our config, never from the
     stored bytes: no `@class` default typing, no Java serialization);
   - bulk clear with **SCAN** batches (never `KEYS`); statistics on (Micrometer `cache.*`);
   - `LoggingCacheErrorHandler` + 500 ms Redis timeout: a Redis failure is a miss, not an outage;
   - `SEATLOCK_CACHE_ENABLED=false` → `NoOpCacheManager` (kill switch and "before" benchmarks).
2. **What is cached** (decided from Run 2, median of 3 runs vs the original code):

   | Cache | TTL | Result vs original | Decision |
   |---|---|---|---|
   | `eventDetail` (by event id) | 5 min | p50 −38%, p95 −32% | ✅ keep |
   | `eventSearch` (one list page, normalized criteria + cursor) | 60 s | p50 −22%, p95 −21% | ✅ keep |
   | seat map (venue layout + seat prices) | — | p50 +48%, p95 +59% | ❌ removed |

   The seat map stays **one indexed query**: decoding a cached 252-cell layout and price map
   from Redis cost more than the join it replaced. It also carries live seat status, which
   must never be stale. W3·D1 (status in Redis) will be measured on its own.
   **Update W3·D1:** live HELD status now comes from Redis hold keys via one `MGET` per request;
   still uncached, no measurable cost vs the W2·D6 code (`benchmarks.md` section 2).
3. **Cache hits never touch the database.** The cache advice runs before the transaction advice
   (`@EnableCaching(order = HIGHEST_PRECEDENCE)`) and the read services are not `@Transactional`;
   only a miss opens its read-only transaction (`CatalogReadCache`). Verified by
   `eventDetailCacheHitBorrowsNoDatabaseConnection` (HikariCP usage count unchanged on hits).
4. **Correctness rules**
   - Event detail drops shows that started since it was cached (per request).
   - List keys are normalized like the SQL compares (city/language case-insensitive, past
     `startsAfter` = none) and URL-encoded so a crafted `q` can't forge another field.
   - Invalid cursors are rejected (400) before anything is cached; exceptions are never cached.
5. **Eviction after commit.** `createShow` publishes `ShowScheduledEvent`;
   `@TransactionalEventListener(AFTER_COMMIT)` evicts `eventDetail::{eventId}` and clears
   `eventSearch`. A rolled-back change evicts nothing.

## Consequences
- Bounded staleness: a reader that loaded data just before a commit can re-cache the old copy
  after our eviction; it lives at most one TTL (5 min detail, 60 s lists). Acceptable for catalog
  data; never acceptable for seat status, which is not cached.
- Benchmarks are single-client latency on a laptop (session-to-session noise is large). Behaviour
  under concurrent load is re-measured with k6 on W6·D3; caches are revisited there.
- The hit rates seen in benchmarks (~100%) come from repeating one key; they are not production
  hit rates.
