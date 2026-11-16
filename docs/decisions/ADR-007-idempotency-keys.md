# ADR-007: Idempotency keys for unsafe POSTs (starting with `POST /holds`)

**Date:** 2026-11-16 · **Status:** Accepted

## Context

Clients retry: a mobile network drops the response, the user double-clicks "Hold seats", a fetch wrapper
retries on timeout. Without protection a retry of `POST /holds` either fails confusingly (`HOLD_EXISTS` for
the user's *own* hold) or, for future endpoints like payments, does the work twice. The API runs on two
instances (W5), so the deduplication must live in shared storage.

## Options considered

| Option | Verdict |
|---|---|
| **A. `Idempotency-Key` header + Postgres table + interceptor on `@Idempotent` methods** | ✅ chosen (plan: "table + interceptor/aspect") |
| B. Same, stored in Redis (`SET NX` + TTL) | Simpler expiry, but Redis has no persistence in prod (#64): a restart forgets keys exactly when retries spike |
| C. Servlet filter that buffers raw responses | Works for any endpoint, but must buffer bytes, can't see exceptions, awkward with errors |
| D. Rely on the operation's own rules (`HOLD_EXISTS`) | Not idempotent: the retry gets an error instead of its result; no help for payments later |

## Decision

1. **Header `Idempotency-Key`**, required on `@Idempotent` endpoints (user-approved): 8–64 chars `[A-Za-z0-9_-]`
   (a UUID per attempt). Missing → 400 `IDEMPOTENCY_KEY_REQUIRED`; malformed → 400 `INVALID_IDEMPOTENCY_KEY`.
2. **Table `idempotency_keys` (V7)**, unique `(user_id, idem_key)` → keys are per user. The first request claims
   the key with `INSERT … ON CONFLICT DO NOTHING RETURNING id`; the unique constraint decides races. Every store
   call is one auto-committed statement so a claim is visible to concurrent duplicates immediately.
3. **Fingerprint** = SHA-256 of `METHOD path` + the `@RequestBody` re-serialized as JSON. Same key, different
   request → **422 `IDEMPOTENCY_KEY_REUSED`**.
4. **Outcomes:** 2xx → stored (status + body) and **replayed** for 24 h with `Idempotent-Replayed: true`
   (headers such as `Location` are not stored). Any exception → the key is **released**, so a retry runs
   again; only valid for operations whose failures have no side effects (holds are all-or-nothing).
   Duplicate while the first is running → **409 `IDEMPOTENCY_IN_PROGRESS`**.
5. **Crash between the operation and storing its response:** the row stays `IN_PROGRESS`. After a **30 s lease**
   (`locked_at`) a retry takes it over with a compare-and-set `UPDATE` and runs the operation again, which then
   answers for itself: for holds, 409 `HOLD_EXISTS` **with the `holdId`** so the client can continue. If storing
   the response fails after success, the client still gets its result and the key is not released.
6. **Mechanism:** a plain Spring AOP `Advisor` (`@Idempotent` pointcut, infrastructure role, lowest precedence →
   runs after `@PreAuthorize`), no AspectJ dependency; the advisor looks the interceptor bean up lazily.
7. **Retention:** 24 h (user-approved), deleted hourly by `IdempotencyCleanupJob` (safe on two instances:
   DELETE is idempotent). Scheduling is off in the test profile.

## Consequences

- One extra indexed INSERT + UPDATE per idempotent request; negligible next to the operation.
- Replays are exact for status + body; clients must not depend on response headers of a replay.
- New unsafe endpoints (payments, W4) get the same protection by adding `@Idempotent`, but only if their failures
  are side-effect free; otherwise they need their own compensation (Razorpay has its own idempotency too).
- Tested: `IdempotencyStoreIntegrationTest` (incl. 20 concurrent claims → 1 winner), `IdempotencyInterceptorTest`
  (decision table), `HoldIdempotencyIntegrationTest` (replay → same holdId, 422, 400, retry after failure,
  10 concurrent duplicates → one hold), `IdempotencyCleanupJobTest`.
