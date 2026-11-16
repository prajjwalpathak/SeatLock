# ADR-009: Rate limiting `POST /holds` with Bucket4j token buckets in Redis

**Date:** 2026-11-17 · **Status:** Accepted

## Context

Holds are the contended, abuse-prone endpoint: a script can grab and release seats in a loop (denying them to
real users) or hammer Redis. Idempotency keys stop *duplicate* requests but not *many different* ones. The API
runs on two instances (W5), so limits kept in each JVM would double the real limit.

## Options considered

| Option | Verdict |
|---|---|
| **A. Bucket4j `bucket4j_jdk17-lettuce` 8.22.0, buckets in Redis (CAS)** | ✅ chosen (plan's locked stack: Bucket4j core + Lettuce) |
| B. Own Lua token bucket (`RedisScript`, like `hold_seats.lua`) | Fallback if Bucket4j were incompatible with Lettuce 7; not needed |
| C. In-memory Bucket4j per instance | Limits multiply with instances; lost on restart |
| D. Nginx `limit_req` only | Coarse (IP only, no user), already used for login/register; no per-user policy |

## Decision

1. **Policy `holds`**: per IP 30/min (capacity 30), per user burst 5 then 10/min (`seatlock.rate-limit.policies.holds`).
   IP first (cheap, stops anonymous floods), then user. The first empty bucket → **429 `RATE_LIMITED`** with
   `Retry-After` (seconds, rounded up) and `retryAfterSeconds` in the body; allowed responses carry `RateLimit-Remaining`.
2. **Mechanism**: `@RateLimited("holds")` on the controller method, applied by a Spring MVC `HandlerInterceptor`
   (after Spring Security, so the user id is known). Keys `rl:{policy}:ip:{addr}` / `rl:{policy}:user:{id}`; buckets
   expire ~10 s after they'd be full again, so idle keys cost nothing.
3. **Same Redis client as the app** (`LettuceConnectionFactory.getRequiredNativeClient()`): same host, timeouts and
   `REJECT_COMMANDS` fail-fast. The proxy manager is built on first use (the native client only exists once the
   context has started).
4. **Fail open**: if the bucket store can't be reached, requests are allowed. Rate limiting protects, it doesn't
   guarantee correctness; the hold itself answers 503 `HOLDS_UNAVAILABLE` when Redis is down.
5. **Client IP** from `request.getRemoteAddr()`, correct behind Nginx because prod sets
   `server.forward-headers-strategy: native`.
6. **Compatibility**: Bucket4j's Lettuce module declares `lettuce-core` 6.1.8 as `provided`; Boot 4.1 ships Lettuce 7.5.2.
   `RateLimiterIntegrationTest` exercises it against real Redis with Lettuce 7 (it would fail with `NoSuchMethodError`
   if the binary API had changed).
7. **Tests** run from one IP (`127.0.0.1`), so the test profile uses a very high IP limit and 20/min per user;
   `RateLimiterIntegrationTest` checks the real token-bucket behaviour with small limits.

## Consequences

- One extra Redis round trip per protected request (two with a signed-in user); negligible next to the hold's Lua call.
- The interceptor runs before `@Idempotent`, so a replayed request also costs a token (bounded and cheap).
- Users behind one NAT share the IP bucket; 30/min is generous for real clicking. The per-user bucket is the real limit.
- Other endpoints opt in with `@RateLimited("<policy>")` + a policy entry (payments, W4; AI quota, W7).
