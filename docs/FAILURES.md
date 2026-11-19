# SeatLock: Failure Drills

What I broke on purpose (or found broken), what happened, the fix, and before/after numbers.
Each drill can be repeated with the commands shown.

| # | What broke | Found by | Lesson |
|---|---|---|---|
| F1 | Redis down → every seat map waited 512 ms | failure drill | fail fast (`REJECT_COMMANDS`), degrade, measure before/after |
| F2 | Cache writes not visible right after "put" | CI-only flake | async defaults in libraries; diagnose the mechanism, not the symptom |
| F3 | Merge silently disabled rate limiting | CI test + startup log | never "accept incoming"; features that can be off must say so |
| F4 | Squash merge reverted a day's docs | stash conflict | diff the branch head against the merged result after every merge |
| F5 | Locked re-read clashed with an unlocked copy (500s) | 20-request race test | first read of a row in a locking transaction must be the locked one |
| F6 | STOMP ERROR frame lost before the close | CI-only flaky test | after CONNECT, rely on the close, not on the ERROR frame |
| (W4·D3) | Webhook secret with `$` cut by Compose interpolation | prod delivery logs | no `$` in env files; hex secrets; check compose warnings |
| (W4·D5) | HTTP/2 h2c upgrade / stream resets to the provider | WireMock over real HTTP | pin provider clients to HTTP/1.1 |

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
| After (`REJECT_COMMANDS`) | **~5 ms** (p50 4.5–5.5 ms, `benchmarks.md` section 2, variant B3) | ~10 ms within 3 s, `holdsLive: true` |

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

## F3. A merge that silently disabled rate limiting (W3·D5, 2026-11-17)

**Symptom:** CI red on one test only: `HoldRateLimitIntegrationTest` expected `RateLimit-Remaining: 19`, got no
header at all. No "Rate limiter unavailable" warning either. Locally the same test later failed the same way.

**Wrong turns (each fixed something real, none fixed this):**
1. Locally, `RateLimiterIntegrationTest` failed with `RedisException: Connection is closed`: Spring 7 pauses cached
   test contexts, which stops `LettuceConnectionFactory` and shuts its client down; the limiter's cached Bucket4j
   proxy still used the old client → `RateLimiter` became a `SmartLifecycle` that drops the proxy on stop.
2. `@WebMvcTest` slices failed to start (they load every `WebMvcConfigurer`, the limiter needs Redis) → optional
   `ObjectProvider` wiring, later replaced by a `@Bean WebMvcConfigurer` (deterministic, logs at startup).
3. Made the limiter self-heal: on failure drop the proxy and retry once on a fresh one, then fail open.

**Root cause:** the startup line added in step 2 printed `Rate limiting DISABLED for policies [holds]`.
`application.yml` no longer had a `seatlock.rate-limit` block. `w3-d5` had been branched from `w3-d4` before #4
was squash-merged; GitHub then merged `main` into the branch, every file both days touched conflicted, and the
conflicts were resolved with "accept incoming", which **dropped 99 lines of the branch's own changes**
(`application.yml`, `CORRECTNESS.md`, `architecture.md`, `progress.md`). With the block gone, `enabled`
defaulted to `false` and the interceptor let everything through.

**Fix:** restored the four files from the branch's last pre-merge commit (`ac12ede`, which already contained
`main`'s content) and pushed; CI green.

**Lessons:**
- Never resolve squash-merge conflicts with "accept incoming/current"; rebase instead
  (`git rebase --onto origin/main <last commit of the old day>`), and create a day's branch only after the
  previous PR is merged (#96, now enforced in the workflow).
- A feature that can be switched off should **say so at startup**. One log line ended an hour of guessing.
- When a test says "header missing", first prove the code path ran at all before debugging the code inside it.

## F4. A squash merge that quietly reverted a day's docs (W3·D6 → W4·D1, 2026-11-18)

**Symptom:** after #6 (W3·D6) was squash-merged, `main`'s `progress.md` still said "Current day: Week 3 · Day 5" and
lacked decisions #104–#115; `CORRECTNESS.md` lacked section 1h. Code and tests were intact, CI was green, so nothing failed.
Found only because the next day's stash pop conflicted on `progress.md`.

**Root cause:** the same pattern as F3. `w3-d6` was cut before #5 was merged; the PR's conflicts on shared docs were
resolved in favour of `main`'s older copies. A second mistake followed on W4·D1: the new branch was created before the
merge, then work was committed on `main` with the stash's `<<<<<<<` markers inside `progress.md`.

**How it was found:** `git diff --stat 4e5d154 origin/main` (the branch's last commit vs the merged result) showed
exactly two files with only deletions: `CORRECTNESS.md` (−40) and `progress.md` (−102).

**Fix:** restored both files from `4e5d154`, re-applied the day's change (#116); moved the stray commit with
`git branch -f w4-d1 HEAD` + `git branch -f main origin/main` (it had never reached `origin/main`).

**Lessons:**
- After every squash merge, check `git diff --stat <branch head> origin/main`: it must be empty (or only show `main`'s newer work).
- Green CI doesn't protect docs; a merge can lose prose without breaking a single test.
- Grep for conflict markers before committing: `git grep -n "^<<<<<<<\|^>>>>>>>"`.

## F5. Locked reads that clashed with an unlocked copy (W4·D3, 2026-11-19)

**Symptom:** `PaymentConfirmRaceIntegrationTest` (10 checkout callbacks + 10 webhooks for one payment, at once): 2–7 of
20 requests answered **500** in every run. All single-request tests were green. Correctness held (one CONFIRMED,
seats BOOKED), but losing requests failed instead of answering `ALREADY_DONE`, and a failed webhook means a retry storm.

**Root cause:** `HibernateException: Query result contains conflicting version of entity already held in persistence
context`. Inside one transaction a row was first read **without** a lock, then again with `SELECT … FOR UPDATE`:
1. the webhook's "is this our order?" check loaded the `Payment` (version N); `capture()`'s locked read then waited for
   the winner and returned version N+1;
2. `capture()` loaded the `Booking` (`publicIdOf`) before `confirmPayment()` locked it.
Hibernate 7 won't silently refresh a managed entity with a newer version, so the locked query throws. With one request
nothing changes between the two reads, which is why only the race exposed it.

**Fix:** never load an entity unlocked before locking it in the same transaction: the webhook uses
`existsByGatewayAndGatewayOrderId` (loads no entity), and `capture()` resolves the booking's public id only after
`confirmPayment()` (or on paths that never lock it).

**Lessons:**
- In a transaction that locks a row, the **first** read of that entity must be the locked one.
- Correctness assertions alone weren't enough: the race test also asserts every request gets a 2xx, which caught it.
- "Works for one request" says nothing about lock-and-reread code; concurrency tests belong next to every row lock.

## F6. STOMP ERROR frames lost in a write/close race (W5·D2 CI, 2026-11-19)
- **What happened:** `StompSecurityIntegrationTest.onlySeatTopicsCanBeSubscribedTo` failed once on GitHub Actions: a refused
  SUBSCRIBE got no ERROR frame (`null`), though the same test passed locally and in the W5·D1 CI run.
- **Why (most likely, not reproduced):** since W5·D2 the broker preserves publish order, so CONNECTED is written from a pooled
  outbound thread. A refused frame's ERROR is written from the inbound thread, and Spring closes the session right after.
  `ConcurrentWebSocketSessionDecorator` allows one writer at a time: if CONNECTED is still being flushed, the ERROR is only
  buffered, and the immediate close can discard it.
- **Impact:** a client refused after CONNECT may see a close without the reason. Security is unchanged (no subscription, no
  forwarded SEND, connection closed). `INVALID_TOKEN` on CONNECT is not affected (nothing was written before it).
- **Fix:** tests for post-CONNECT refusals assert that the server closes the connection, and that the code is the expected one
  whenever the ERROR frame arrives (`StompTestClient.awaitRejection`). Revisit if real clients need reliable reasons.
