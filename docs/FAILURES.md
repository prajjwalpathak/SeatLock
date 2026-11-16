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
