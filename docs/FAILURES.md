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
| F7 | Prod 502s after the 2-instance cutover; then "WebSocket failed" in one browser | `curl` loop on the VM; Nginx access log; clean built-in browser | changed bind-mounted config needs `--force-recreate`; reproduce in a clean browser before changing the server |
| F8 | Drills on 2 instances; Postgres outage made requests hang 30 s | `load-tests/failure-drills.ps1` | fail fast on missing dependencies (3 s pool wait, 503 + Retry-After), like F1 for Redis |
| F9 | Squash of #19 shipped code without the day's docs | `git diff origin/main w5-d6` before the next branch | never use GitHub's "Update branch"/merge-main on a daily PR; rebase instead (#110 again) |
| F10 | Grafana never started on prod (502 at `/grafana/`) | `docker stats` during the W6·D2 rollout | a memory limit below the working set doesn't OOM-kill, it thrashes: watch block I/O, size limits from measured startup, not idle use |
| F11 | 25–50 × 503 `HOLDS_UNAVAILABLE` in a 500-user flash sale | k6 `unexpected_responses` threshold + app logs | a fail-fast timeout must leave headroom for a saturated CPU; under 2 GB the JVM silently picks Serial GC |
| F12 | Redis granted a hold on a seat booked a moment earlier (2 winners per slot, 409 at checkout) | k6 per-slot threshold `seat_wins{slot} <= 1` | a pre-check is a snapshot: re-check after a claim whenever the other side releases its claim after committing |
| F13 | Prod stuck on W6·D2 for 18 h: 3 deploys failed (`deploy.sh: Permission denied`) + a smoke test that could only see one instance | prod check on W6·D6 (`/system/version` ≠ `main`) | red Deploy runs need an alarm or a daily check; every copy to Linux must restore exec bits; a check whose two requests alias with round robin tests nothing |
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

## F7. Cutover 502s and a browser-only WebSocket failure (W5·D5, 2026-11-19)
- **502 after `dc up -d --remove-orphans`:** the old `app` container was removed and `app-1`/`app-2` came up healthy, but
  every request returned 502. The Nginx container ID was unchanged: Compose doesn't recreate a service when only a
  bind-mounted file (the template) or `depends_on` changes, so Nginx still proxied to `app:8080`.
  Fix: `dc up -d --force-recreate --no-deps nginx` (now in deploy.md section 3b). Later deploys don't touch Nginx
  (`resolve` re-resolves app-1/app-2).
- **"WebSocket connection to wss://…/ws failed" with no reason**, provisional headers only, in the user's browser and phone,
  while `curl` (also with browser-identical STOMP subprotocol and compression headers) got `101`. Nginx's access log showed
  only the curl handshakes: the browser requests never reached the VM. A clean browser on the same machine and network
  (the Claude app's built-in browser) connected in 198 ms, showed **Live**, and reflected a hold made through the API within 2 s.
  Cause: **Brave Shields** blocking the cross-site WebSocket from `seatlock-nu.vercel.app` to `seatlock-prajjwal.duckdns.org`.
- **Impact:** visitors whose browser blocks it get no live updates (badge stays "Connecting…"); the map still updates on
  focus, Refresh and after their own actions. Lasting fix (stretch, W8): serve frontend and API under one site
  (e.g. `app.` + `api.` subdomains of one domain) so the socket isn't cross-site.
- **Lesson:** a symptom seen only in browsers is checked in a clean browser and against the server's access log before
  any server change.

## F8. Failure drills on two instances (W5·D6, local stack)
Run with `.\load-tests\failure-drills.ps1 -Drill <name>` against `infra/docker-compose.scale.yml`; a probe polls the seat
map through the LB every 200 ms.

| Drill | Expected | Measured |
|---|---|---|
| `instance`: `docker stop app-2` for 20 s | GETs keep working (Nginx moves failed connects to app-1); app-2 back in round robin after restart | ✅ **238/238 requests 200**, 0 errors (app-1 198, app-2 40); stop 0.7 s (graceful); app-2 healthy 16 s after start, round robin alternating again; slowest request 2.2 s |
| `booking`: book on app-1, `docker kill app-1` | booking readable and cancellable through app-2; seat AVAILABLE after cancel; nothing lost | ✅ GET via LB 200 from app-2 (HELD), seat HELD, cancel 200 (CANCELLED), seat AVAILABLE; app-1 healthy again 21 s after start |
| `redis`: `docker restart redis` | seat map stays 200 with `holdsLive: false` while down; holds 503; relay re-subscribes (≤ 2 s recovery interval) | ✅ Redis back in 0.7 s; 191/191 seat-map requests 200 (4 degraded, `holdsLive: false`), slowest 2.2 s; fully live 0.56 s after; cross-instance relay working again 3.2 s after (first check) |
| `postgres`: stop 15 s | readiness DOWN; seat map 5xx while down; recovers by itself after start (Hikari reconnects) | ⚠️ **before fix:** requests hung until the 5 s client timeout (10 × no response, 1 × 500), readiness call ~30 s (Hikari's default 30 s `connection-timeout`); recovered 5.1 s after start. **Fix:** `connection-timeout: 3000` + DB-unreachable → 503 `SERVICE_UNAVAILABLE` + `Retry-After: 5`; **after:** 503 within ≤ 3 s (slowest request 3.0 s, 8 × 503, no hung requests), readiness answers at once, fully back **1.3 s** after start (was 5.1 s); outage window 21 s stop→start (was 48 s, the 30 s readiness wait) |
| Side finding (drill logs) | jobs on their own scheduler | ⚠️ `RefundWorker` ran on `MessageBroker-2`: `@Scheduled` jobs shared the STOMP broker's scheduler (heart-beats). **Fix:** `SchedulingConfig` gives jobs their own `scheduling-N` pool (2 threads) |
| Browser on the killed instance | badge Reconnecting…, then Live on the other instance; map reloaded once | ✅ `docker stop` of the viewer's instance → **Live again on app-2 in < 1 s** (first reconnect attempt, 1 s backoff + jitter; Nginx skips the stopped instance) |

## F9. "Update branch" dropped a day's docs (W5·D6 → W6·D1, 2026-11-20)
- **What happened:** `w5-d6` started with the W5·D5 docs commit, which was merged separately as #18. GitHub then showed
  #19 as conflicting (same lines changed on both sides) and the branch was updated with **"Merge branch 'main' into w5-d6"**
  (`f3b6864`), resolved in favour of `main`. That merge removed the W5·D6 progress entry, the Week 5 review, decisions
  #172–#175, the drill metrics and FAILURES F8; the squash of #19 shipped the code without them.
- **Found by:** `git diff origin/main w5-d6` while creating the next branch (68 lines only on the branch).
- **Fix:** restored both files from the local `w5-d6` onto `w6-d1` (`git checkout w5-d6 -- progress.md docs/FAILURES.md`).
  `frontend/.env.example` (accidentally set to the LB port) reverted to 8080 in the same commit.
- **Rule (extends #110):** never click GitHub's "Update branch" or resolve a daily PR's conflicts in the web editor. Stop and
  rebase locally: `git rebase --onto origin/main <last commit already merged>`, then `git push --force-with-lease`.
  Day-end docs go into the branch **before** its PR is merged, never into a separate PR from the same branch.

## F10. Grafana starved by its memory limit (W6·D2 rollout, 2026-11-20)
- **What happened:** after `dc up -d prometheus grafana`, `/grafana/login` returned **502** for 7+ minutes. The container
  was `running`, `OOMKilled=false`, 0 restarts, at **171.3 / 192 MiB** with **17.6 GB of block reads**: the kernel kept
  evicting Grafana's binary/page cache under the cgroup limit and reading it back, so first-start migrations crawled and
  port 3000 never opened.
- **Found by:** `docker inspect` (not OOM-killed) + `docker stats` (memory at the limit, huge block I/O) + logs still in
  API registration.
- **Fix:** limit **192 → 384 MB** and `GF_UNIFIED_ALERTING_ENABLED=false` (no alert rules; skips Alertmanager and the rule
  engine). After recreation: 200 at `/grafana/login`, Grafana **126.8 MiB**, VM 2.1 GiB available.
- **Lesson:** a limit below the working set doesn't always kill a process; it can thrash instead. Size limits from a
  measured *startup*, and treat high block I/O on a capped container as memory pressure.

## F11. 503s during a 500-user flash sale (W6·D3, local 2-instance stack)
- **What happened:** `flash-sale.js` with 500 users / 10 seats got 20 × `503 HOLDS_UNAVAILABLE` (holds and one
  booking); 300 users / 20 seats had none. Nothing was written and nothing was oversold, but users who could have had
  a seat got an error.
- **Found by:** k6 `unexpected_responses: count==0`; app logs: `QueryTimeoutException: Redis command timed out`
  (500 ms), on **one** instance only, even for a single-key `GET` (seat-event version), within ~1.5 s.
- **Investigation:**
  1. One instance, tiny commands too → the client side stalled, not Redis. The container JVM ran **Serial GC**
     (`-XX:+UseSerialGC`: the JVM's choice under 2 GB, our limits are 900/1200 MB). Prometheus: young 0.2 s, full
     GC **1.245 s**. Switching to G1 (`-XX:+UseG1GC` in the Dockerfile) cut pauses to 0.15 s; warm runs were clean.
  2. But the **first sale after a (re)start** still failed on both collectors (G1: 50 × 503, Serial: 25 × 503).
     The browse warm-up warms only reads; the hold path (JIT, first Lua `SCRIPT LOAD`, rate limiter, idempotency)
     was cold, and 500 simultaneous requests saturate the laptop's CPU (2 JVMs + Postgres + Redis + k6).
- **Fix:** G1 explicitly (pauses) + Redis command timeout **500 ms → 2 s** (a *down* Redis still fails at once via
  `REJECT_COMMANDS`, F1; the timeout only bounds a slow/hung one). Result: cold first sale **0 × 503** (hold p99
  7.3 s instead of errors), warm sale 0 × 503. Also: hold counters registered at startup (they were invisible to
  `increase()` on their first scrape).
- **Lesson:** a fail-fast timeout set on an idle machine turns load into errors; size it for a saturated CPU and
  keep fast failure for the *down* case separately. Check which GC the container actually runs.

## F12. A hold granted on a seat that had just been booked (W6·D3)
- **What happened:** in one Serial-GC run, k6 counted `seats_won: 12` for 10 seats; slots 0 and 3 were won twice. One
  winner's booking then failed `409 SEATS_UNAVAILABLE [938, 935]`. The DB invariant stayed 0: no oversell.
- **Cause:** `HoldService` checks Postgres (AVAILABLE) **before** the Redis claim. User A's hold → booking commits
  (seats HELD in Postgres) and only then drops A's Redis keys; B's pre-check ran before A's commit and B's Lua claim
  after A's keys were gone, so Redis said yes. A 1.2 s GC pause between B's two steps stretched a microsecond window.
- **Fix:** re-check Postgres **after** a successful claim; if a seat is no longer AVAILABLE, release the new hold and
  answer the normal 409 (`seatlock.holds{outcome=booked_during_claim}`). Complete, not probabilistic: A's keys
  disappear only after A's commit, so a claim that succeeded because they were gone sees that commit. Cost: one
  indexed query per **winning** hold. Tests: `HoldServiceTest` (seat booked during the claim, show no longer
  bookable). Later runs: 0 double wins, 0 `booked_during_claim`.
- **Lesson:** the per-slot k6 threshold caught what status codes and the DB invariant alone would have hidden (the DB
  guard absorbed it); assert the invariant at every layer, not only the last one.

## F13. Three deploys failed silently; the smoke test could only see one instance (W6·D2 → W6·D6, 2026-11-20)
- **What happened:** prod stayed on `d0ab161` (W6·D2) while #22–#24 merged: no G1, 500 ms Redis timeout, no V12.
  GitHub: every Deploy run since #20 red, every CI run green.
- **Cause 1 (#29–#31):** `deploy.sh: Permission denied` (exit 126). The W6·D3 Grafana fix (F10) re-copied `infra/`
  with a Windows `tar` and unpacked it without `chmod +x`; Windows archives don't carry the executable bit, and the CI
  key's forced command executed the file directly. Deploys stopped before touching the containers (up 18 h).
- **Cause 2 (#28, and the #31 re-run):** the deploys succeeded but the smoke test failed: `seen: app-1` (later
  `seen: app-2`) only. Each loop iteration made two requests (readiness, then version); on a quiet site Nginx's round
  robin alternates strictly, so the version request always landed on the same instance. It passed on W5·D5 only
  because other traffic broke the alternation.
- **Found by:** the W6·D6 prod check: `/api/v1/system/version` showed `d0ab161` while `main` was `f697b6a`; the
  failing steps' logs; `ls -l` on the VM.
- **Fix:** `chmod +x infra/scripts/*.sh` on the VM; forced command now `/bin/bash …/deploy.sh` (permissions can't block
  it again); smoke test makes one request per iteration, then 4 readiness requests (each instance twice); deploy.md
  section 3d "is prod on `main`?" at the start of every day; every infra copy ends with `chmod`.
- **Lesson:** a red deploy that blocks nothing is invisible: check prod's revision, don't assume. And a check built
  from two interleaved requests against a strict round robin can't see half the system.

