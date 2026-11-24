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
every AVAILABLE seat's hold key, merged into the DB statuses). Same script and method as section 1
(`load-tests/bench-catalog.ps1 -Only seats -ShowId 34`, 200 warm-up + 500 timed requests,
`LOGGING_LEVEL_COM_SEATLOCK=INFO`, median of the runs). Fake holds written with `load-tests/seed-holds.ps1`.
**Machine:** same laptop as section 1.

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

> W4·D6 preview: races A/B now also run with `seatlock.holds.strategy=optimistic`. Same laptop, same `mvnw verify`
> run (not a benchmark, single runs): race A p50 0.87–0.97 s / p99 1.84–2.13 s (redis-hold) vs p50 1.15–1.48 s /
> p99 1.94–2.51 s (optimistic). The controlled comparison (throughput, conflicts, p99, repeated sessions) is W6·D4.

From the concurrency tests (`docs/CORRECTNESS.md` section 1h). Real HTTP to a real Tomcat port, Testcontainers
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


## 4. k6 load tests: browse + flash sale (W6·D3)

**Setup.** Windows laptop, local 2-instance stack (`docs/local-scale.md`: app-1 + app-2, prod profile, 1200 MB limits,
Nginx LB on `localhost:8088`), local Postgres 16 + Redis 7 in Docker, mock payment gateway, `redis-hold` strategy.
k6 runs on the same laptop (it competes for CPU, so absolute numbers are conservative and laptop-relative; they are
not prod capacity). Grafana (`docs/monitoring.md`) watched during each run.

**Scripts** (`load-tests/`, run with `.\load-tests\run-k6.ps1 -Script <name>`):
- `browse.js`: anonymous visitors, ramping to `VUS` (default 50) for 2.5 min: event list (1 in 3 filtered by city),
  event detail, seat map, 1–5 s think time between steps. Gates: errors < 1 %, p95 < 250 ms (list, detail),
  p95 < 300 ms (seat map).
- `flash-sale.js`: `USERS` (default 300) fresh accounts start at the same moment for a block of `SEATS` (default 20)
  seats of one show. Each user reads the seat map, holds 1–2 free block seats, re-reads and retries on 409 (up to 3
  tries), and converts a won hold into a booking. Every user gets a distinct `X-Forwarded-For` from 198.18.0.0/15, so
  the per-IP hold limit applies per user as it would on the internet (Tomcat trusts the Docker hop; in prod Nginx
  appends the real client IP, so a spoofed header changes nothing there).
- **Oversell checks:** k6 thresholds `seat_wins{slot:N} <= 1` for every sale seat and `seats_won <= SEATS`; then
  `run-k6.ps1` runs the invariant SQL (`booking_seats` live claims per seat > 1) against Postgres: must be 0.

**Latency is reported, not gated** in the flash sale: a 300–500-user burst on one laptop measures queueing (k6 on
the same CPU, 10 DB connections per instance), not a capacity target. Correctness rules fail a run; browse keeps its
latency gates (50 VUs is a normal load).

### Results (2026-11-20)

**Browse, 50 VUs, 2.5 min steady state** (final code: G1, 2 s Redis timeout)

| Request | p50 | p95 | p99 | max |
|---|---|---|---|---|
| `GET /events` (1/3 filtered by city) | 5.3 ms | 9.9 ms | 14.7 ms | 51.5 ms |
| `GET /events/{id}` | 4.2 ms | 6.4 ms | 8.1 ms | 25.6 ms |
| `GET /shows/{id}/seats` (live hold overlay) | 6.8 ms | 11.1 ms | 15.5 ms | 38.4 ms |

0.00 % errors, all thresholds passed (first run, Serial GC: 3603 requests at 17.4/s, p95 6.7–12.4 ms: same picture).

**Flash sale: correctness in every run.** 15 sales (300 users / 20 seats and 500 users / 10 seats, Serial and G1,
cold and warm): **oversells = 0** every time (k6 `seat_wins{slot} <= 1`, `seats_won <= SEATS`, DB invariant SQL 0).
Two runs exposed real defects (FAILURES F11, F12), both fixed below.

**Flash sale, final code** (G1 + post-claim re-check + 2 s Redis timeout), 500 users → 10 seats, up to 3 tries each

| Run | Winners / bookings | Conflicts (409) | Sold out | 503s | Hold p50 / p95 / p99 | Seat map p95 | Booking p95 |
|---|---|---|---|---|---|---|---|
| 1st sale after a restart (cold hold path) | 7 / 7 | 493 | 493 | **0** | 4.04 / 6.56 / 7.33 s | 3.96 s | 3.58 s |
| 2nd sale (warm) | 6 / 6 | 375 | 494 | **0** | 1.73 / 2.43 / 2.76 s | 2.13 s | 2.83 s |

~2.3–2.5k requests in ~30 s per sale (≈70 req/s, all users arriving within ~1 s). Hold outcomes from Prometheus
(`seatlock_holds_total`) match k6: e.g. warm run 404 → 786 `seats_taken`, 0 `booked_during_claim`.

**Serial vs G1 (F11), 500 users / 10 seats, 2 s timeout not yet applied (500 ms)**

| Collector | Runs | 503s per run | Longest pause |
|---|---|---|---|
| Serial (JVM default under 2 GB) | 6 | 20, 8, 0, 0*, 25 (first after a restart), 0 | young 0.235 s, **full 1.245 s** |
| G1 | 6 | 0, 0, 0, **50** (first after a rebuild), 0, 0 | young 0.15 s |

\* the run that exposed F12. G1 removed the long pauses but not the cold-start 503s; the 2 s timeout did.

**Takeaways**
- The guarantee held under every burst: Redis decides holds, the post-claim re-check catches a just-booked seat,
  and the partial unique index is the final word.
- Contention costs latency, not errors: losers get a clean 409 (or "sold out" on their next look) within seconds.
- A cold hold path is ~2× slower than a warm one (JIT, first Lua load, pools): prod warm-up matters (W8·D2: CDS).
- Fail-fast timeouts must leave room for a saturated CPU: 500 ms turned queueing into 503s.

## 5. Locking strategy and virtual threads (W6·D4)

**Question.** Does `optimistic` (Postgres conditional upserts, ADR-013) cost more than `redis-hold` (Lua + TTL), and do
virtual threads change throughput or tail latency for the hold path?

**Matrix.** {`redis-hold`, `optimistic`} × {platform, virtual request threads} = 4 configs, each with:
- **Throughput** (`load-tests/strategies.js`): closed loop, 50 VUs (each its own user) for 60 s, hold 1–2 random seats of
  the show with the most free seats → release, no think time. Low contention: measures the cost of a hold.
- **Contention** (`flash-sale.js`): 500 users → 10 seats at once (section 4), latency and errors under a rush.

**Method** (`load-tests/bench-strategies.ps1`, #61): local 2-instance stack + `infra/docker-compose.bench.yml`
(`BENCH_STRATEGY`, `BENCH_VIRTUAL_THREADS`; rate limiting off for every config, it's the same code and would cap the
numbers). Per config: recreate both apps → verify the config is live (strategy tag on `seatlock_holds_total` in
Prometheus, thread dump without `http-nio-8080-exec-*` = virtual, `Rate limiting DISABLED` in the log) → one
discarded warm-up of each workload (cold hold path, F11) → 3 measured runs of each → **medians**. Same session, same
laptop, G1, 2 s Redis timeout. The DB oversell invariant is checked after every sale.

Conflicts in the throughput loop are ~17–22 % of attempts (409: another VU's live hold on a random pick), the same
proportion for every config: moderate contention, not zero.

### Results (2026-11-20, one session, medians of 3; 0 unexpected responses and oversells = 0 in all 24 runs)

| Config | Throughput: holds/s | Hold p50 / p95 / p99 | Sale (500 → 10): hold p50 / p95 / p99 | Sale req/s |
|---|---|---|---|---|
| **redis-hold / platform** (default) | **336** | **76 / 139 / 188 ms** | **0.54 / 0.93 / 0.97 s** | 71 |
| optimistic / platform | 233 | 115 / 229 / 310 ms | 0.92 / 1.79 / 1.99 s | 78 |
| redis-hold / virtual | 326 | 84 / 157 / 202 ms | 0.82 / 1.39 / 1.57 s | 67 |
| optimistic / virtual | 192 | 133 / 325 / 455 ms | 0.69 / 1.81 / 2.23 s | 70 |

Spread (throughput holds/s, runs 1 / 2 / 3): redis-hold/platform 231 / 336 / 340, optimistic/platform 161 / 233 / 253,
redis-hold/virtual 227 / 326 / 337, optimistic/virtual 166 / 192 / 222. **Run 1 is ~30 % slower everywhere**: the 20 s
warm-up didn't fully warm the JVMs (the script now warms up for a full run). Runs 2–3 alone give the same ranking.

**Takeaways**
- **redis-hold vs optimistic:** +44 % throughput (336 vs 233 holds/s), −39 % p99 (188 vs 310 ms), and about half the
  tail in the rush (0.97 vs 1.99 s). A Lua claim costs less than a Postgres transaction with an advisory lock and a
  multi-row upsert, and it doesn't compete with bookings for the 10 connections per instance. On a **cold** rush,
  `optimistic` exhausted the pool: 12 × 503 `SERVICE_UNAVAILABLE` (Hikari wait > 3 s, #172) in a discarded warm-up;
  warm sales were clean. Both strategies held the guarantee in every run. **Default stays `redis-hold`** (ADR-013).
- **Virtual threads:** no gain; worse tails. redis-hold −3 % throughput (noise) but sale p99 0.97 → 1.57 s;
  optimistic −18 % throughput, p99 310 → 455 ms. The hold path is bounded by 10 DB connections per instance and one
  Redis connection, not by threads, so more threads can't help. Likely mechanism (hypothesis, not isolated): 200
  platform threads cap in-flight requests and Tomcat queues the rest cheaply; with virtual threads all 500 rush
  requests run at once and contend for the pool and Redis together. **Virtual threads stay off.**
- Numbers are laptop-relative (k6 on the same CPU); the comparison is valid within the session, not as prod capacity.

## 6. DB connection pool size: 10 vs 20 per instance (W6·D6)

Same harness as section 5 (`bench-strategies.ps1 -Configs 'redis-hold/platform/10', 'redis-hold/platform/20'`, pool
size via `BENCH_POOL_SIZE`, verified live on both instances through `hikaricp_connections_max`), one session, medians of 3.

| Pool per instance | Throughput (holds/s) | Hold p50 / p95 / p99 | Rush hold p50 / p95 / p99 | Unexpected |
|---|---|---|---|---|
| 10 (default) | 256 | 89 / 187 / 245 ms | 1.24 / 1.88 / 2.05 s | 0 |
| 20 | 276 (+8 %) | 88 / 176 / 233 ms | 1.01 / 1.38 / 1.52 s (−26 % p95/p99) | 0 |

This session ran slower overall than section 5 (256 vs 336 holds/s for the same config): only same-session numbers
are compared (#61).

**Decision: keep 10.** The gain comes from the laptop's many cores. Prod Postgres runs on 2 vCPUs shared with
everything else on the VM; Hikari's sizing guidance is about (cores × 2) + 1 connections in total, and 2 instances × 10
already exceed it. On 2 cores more connections mean more context switching, not more work done. The value is now
explicit in `application.yml` and can be raised per environment (`SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`) on bigger
hardware.


## 7. Startup: JDK 25 AOT cache (W8·D2, corrected W8·D3)

**Question:** does a JDK 25 AOT cache (JEPs 483/514/515, trained at image build with `spring.context.exit=onRefresh`,
Dockerfile) shorten startup enough to keep? Bar set before measuring: **≥ 20 % faster median**, else revert (same rule
as #56).

**Method:** local 2-instance stack (`docker-compose.local.yml` + `docker-compose.scale.yml`, prod profile, AI on,
1200 MB limits), `load-tests/measure-startup.ps1`: 5 restarts × 2 instances = 10 starts per variant, Spring Boot's own
`Started SeatLockApplication in X seconds (process running for Y)`, median.

**Run 1 (W8·D2), both variants WITHOUT the model:** baseline = the exact prod image `sha-af35849bd299`, after = the
W8·D2 image. Neither loaded the embedding model: the Spring AI jar only holds a Git LFS pointer (FAILURES F15), so
the load failed fast in both. Fair to each other, but not prod's real start.

| | Baseline | AOT cache | Change |
|---|---|---|---|
| Median "Started in" | 20.91 s | 14.83 s | −29 % |

**Run 2 (W8·D3), both variants WITH the model loading** (the result to quote): baseline = `sha-af35849bd299` with
`SEATLOCK_RAG_ONNX_MODEL_URI` pointed at the pinned download; after = the W8·D3 image (checksummed model baked in,
clean training layer). Logs checked: `RAG embeddings: all-MiniLM-L6-v2@2.0.2` + policy ingestion in both.

| | Baseline + model | AOT cache + model | Change |
|---|---|---|---|
| **Median "Started in"** | **17.52 s** | **12.44 s** | **−29 %** |
| Median incl. JVM launch | 17.94 s | 13.14 s | −27 % |
| First start (excluded by the median) | 73.5 s (downloads 90 MB) | 54.4 s (cache file cold) | |
| Prod VM (B2als_v2, W8·D2 image, model not loading) | n/a | 12.21 / 12.38 s | |

**Image layers:** AOT cache 174 MB; model 87 MB (+90 MB download stage, cached); the W8·D3 training layer first came
out at **648 MB** because ONNX/DJL unpacked native libraries under the build user's home, fixed by giving the training
run its own home inside the cleaned `/tmp` → **182 MB**.

**Decision:** keep (#242, #247). Varying a lot between starts (laptop): quote the median.

## 8. Memory with the embedding model (W8·D3): why prod keeps embeddings off

Same stack, **heap 60 %** (prod's `MaxRAMPercentage`), model loaded, `MALLOC_ARENA_MAX=2`, after 30 policy searches
(`rag-recall-eval.ps1` through the LB; recall@1 30/30, recall@4 30/30, MRR 1.000, embed + search p50 24 ms / p95 65 ms).

| | MiB per instance |
|---|---|
| `docker stats` | 812 / 820 |
| Heap used (`jvm_memory_used_bytes{area="heap"}`) | ~203 |
| Non-heap (metaspace, code cache) | ~54 |
| AOT cache file mapped (file-backed) | ~174 |
| **Native left** (ONNX Runtime + model file pages + thread stacks + malloc) | **~385** |
| Same image idle, model loaded, heap 70 %, without `MALLOC_ARENA_MAX` | 761 / 774 (native ~430) |

**Rule set before measuring:** enable on prod only if heap **max** + non-heap + native ≤ 850 MB of the 900 MB limit.
540 + 54 + 385 ≈ **980 MB** → **embeddings stay off on prod** (`SEATLOCK_EMBEDDINGS=none`, #248); locally they're on.
Even treating the model's file pages as reclaimable leaves ~890. W7·D6's "+130 MiB with the model" (#234) had been
measured with the LFS pointer, i.e. without the model.

**Option recorded, not taken:** heap 45 % (405 MB) would give ~760 MB worst case, but trades away heap headroom for
real traffic and was only measured idle. Raising both app limits to ~1.1 GB doesn't fit next to Postgres + Grafana on
the 3.8 GB VM.
