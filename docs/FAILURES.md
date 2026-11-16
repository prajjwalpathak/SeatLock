# SeatLock: Failure Drills

What I broke on purpose (or found broken), what happened, the fix, and before/after numbers.
Each drill can be repeated with the commands shown.

## F1. Redis down while users load the seat map (W3·D1, 2026-11-16)

**Drill (local stack):**
```powershell
docker stop seatlock-redis
1..5 | % { curl.exe -s -o NUL -w "%{http_code} %{time_total}s`n" "http://localhost:8080/api/v1/shows/34/seats" }
docker start seatlock-redis
```

**Expected:** the seat map still works (DB statuses, `holdsLive: false`), quickly.

**What happened (before):** 200 with `holdsLive: false` (the fallback worked), but **0.51 s per request**.
Lettuce's default disconnected behaviour queues commands while it auto-reconnects, so every request
waited the full 500 ms command timeout (`spring.data.redis.timeout`) before the fallback ran. With a
real outage, every seat-map request would have been ~80× slower, and every event detail/list cache
lookup would have paid the same 500 ms.

**Fix:** `RedisClientConfig` → `LettuceClientOptionsBuilderCustomizer` with
`DisconnectedBehavior.REJECT_COMMANDS`. A disconnected client now throws at once
(`RedisSystemException`, caught as `DataAccessException`); auto-reconnect stays on.
Test: `RedisClientConfigIntegrationTest` (the option reaches the auto-configured factory).

**After:**

| | Redis down, per request | Recovery after `docker start` |
|---|---|---|
| Before (default Lettuce) | **~512 ms** (5/5 requests) | — |
| After (`REJECT_COMMANDS`) | **~5 ms** (p50 4.5–5.5 ms, `benchmarks.md` §2, variant B3) | ~10 ms within 3 s, `holdsLive: true` |

**Other safeguards that made this a non-event:**
- No DB transaction is open while calling Redis, so the outage couldn't pin Hikari connections.
- Being shown AVAILABLE during the outage can't oversell: holds are rejected atomically by the
  hold script (W3·D2) and the DB partial unique index.

**Remaining limit:** fail-fast covers a *known* broken connection (refused/reset, as with `docker stop`).
A Redis that hangs with the socket still open is bounded only by the 500 ms timeout; a circuit
breaker would cover that (not planned; revisit if it shows up in the W5·D6 drills).

## F2. Cache writes that "happened" but weren't there yet (W2·D6 CI, W3·D2 local, 2026-11-16)

**Symptom:** cache integration tests failed now and then, never in a fixed pattern: a key vanished right
after a write (TTL `-2`) or a "hit" still touched the DB. First seen only on GitHub Actions (W2·D6), then
locally on W3·D2 once the suite grew by 46 tests (more load in the same JVM and Redis container).

**First diagnosis (partly wrong):** `Cache.evict()`/`clear()` may be deferred by the Spring `Cache` contract,
so a late delete landed after the next write → switched to `evictIfPresent()`/`invalidate()` (#76). That fixed
the evictions but not the root cause.

**Root cause:** Spring Data Redis 4's default `RedisCacheWriter` writes **asynchronously** (an internal
`AsynchronousCacheWriterDelegate`; the writer configurer offers `immediateWrites()` to turn it off). So `put`,
`evict` and `clear` could all return before Redis had the change. `CacheConfigIntegrationTest` read the TTL
right after `put` → `-2`.

**Fix:** `RedisCacheWriter.create(factory, c -> c.batchStrategy(scan(1000)).immediateWrites())` in `CacheConfig`.
(The `nonLockingRedisCacheWriter(factory, …)` overload only takes a `BatchStrategy`; a lambda there is read as one,
which is the compile error hit on the way.) Regression test `putsAndEvictionsAreVisibleImmediately`:
200 × put → key exists at once, evict → key gone at once.

**Before / after:** intermittent failures in `CacheConfigIntegrationTest` / `CatalogCachingIntegrationTest`
(CI and local) → 0 in repeated `mvnw verify` runs. Cost: puts happen on cache misses only; a local `SET` is
sub-millisecond, so latency numbers in `benchmarks.md` are unaffected in practice.

**Lesson:** when a fix works "most of the time", look for the mechanism that makes timing matter (here: who
performs the write, and when) instead of patching each symptom.
