# ADR-016: AI gateway with guardrails (Spring AI + Gemini, Resilience4j, Bucket4j quotas, audit)

- **Status:** Accepted (W7·D1)
- **Context:** Week 7 adds two AI features (Seat Finder, Refund Assistant). Both must keep working when the model
  is slow, down, misconfigured or out of free-tier quota (Gemini 3.5 Flash-Lite: 15 RPM, 250K TPM, 500 RPD per
  project). Two app instances share that quota. Free-tier prompts may be used by Google to improve its products.
  Rule from the plan: **AI interprets, code decides** (no DB writes, holds or money from the model).

## Decision
1. **One gateway** (`assistant.ai.AiGateway`) is the only path to a model. It returns a sealed `AiResult`:
   `Answer(text, model, latencyMs)` or `Fallback(outcome)`. "AI unavailable" is never an exception; only caller
   errors are (400 `AI_INPUT_EMPTY` / `AI_INPUT_TOO_LONG` over 300 chars, 429 `RATE_LIMITED` for the user's daily
   quota).
2. **Spring AI 2.0.2** `ChatClient` over `spring-ai-starter-model-google-genai` (API-key mode), behind an
   `LlmClient` port (`GeminiLlmClient` adapter). Guardrails are tested with stub clients, no model needed.
   `spring.ai.model.chat` comes from `SEATLOCK_AI_PROVIDER` and **defaults to `none`**: CI, tests and prod
   without a key start normally with AI disabled (logged at startup) and every feature on its fallback.
   Model id `seatlock.ai.model` (`GEMINI_MODEL`, default `gemini-3.5-flash-lite`), temperature 0.2.
3. **Order of checks (cheapest first):** input → disabled → per-user daily quota → circuit open (no global tokens
   spent) → global daily + per-minute caps → the call.
4. **Resilience4j 2.4.0** (`resilience4j-spring-boot4`, pinned: missing from resilience4j-bom 2.4.0), registries
   from `resilience4j.*` config, decorated in code (no AspectJ, no annotations): circuit breaker `gemini`
   (count window 10, min 5 calls, 50 % failure rate, 30 s open, 2 half-open trials) around a time limiter
   (4 s, cancel the running future). Errors and timeouts count as failures; our own queue rejection doesn't.
   **No retries**: a retry spends free-tier quota twice for one click.
5. **Bulkhead:** calls run on a 4-thread pool with an 8-slot queue per instance; beyond that → `BUSY` at once.
   Request threads never wait longer than the time limit.
6. **Quotas (Bucket4j buckets in Redis, shared by both instances, fail open like all rate limits):**
   per user 5/min (`@RateLimited("ai")`, 429) and 30/day (gateway, 429); global 12/min and 400/day
   (80 % of Google's limits) → `QUOTA` fallback, since it isn't the user's fault.
7. **Audit:** `ai_requests` (V13), one row per request: feature, outcome, model, latency, token counts, input
   length and **SHA-256 of the input, never the text**. Auto-committed insert, failures swallowed. Deleted after
   30 days (`AiRequestCleanupJob`). Metrics: `seatlock.ai.requests{feature,outcome}` (registered at 0),
   `seatlock.ai.latency{feature}` (histogram), plus Resilience4j's own breaker meters.
8. **Admin check:** `GET /api/v1/admin/ai/status` (enabled, model, circuit state), `POST /api/v1/admin/ai/ping`.

9. **Validation hook (W7·D3):** `AiGateway.Options.validatedBy(...)` rejects an answer the caller can't accept →
   `INVALID_OUTPUT` (counted, audited with tokens; the breaker records a success: the model did answer).
   `withoutUserQuota()` exists only for the admin intent eval; global caps always apply.

## Consequences
- Every AI feature needs a non-AI fallback; that is required by the plan anyway.
- A timed-out call may keep its pool thread until the HTTP client gives up; the bounded pool plus the breaker
  (which opens after repeated timeouts) keep that from spreading.
- The global cap is approximate (two buckets, greedy refill, fail-open on Redis errors); Google's own 429 is the
  hard stop and shows up as `ERROR`, which also feeds the breaker.
- Prod stays `none` until the key is added to `infra/.env.prod` (W7·D6).

## Rejected
- `resilience4j-spring-boot3` (its verifier fails on Boot 4); annotation-based `@CircuitBreaker` (needs AspectJ,
  self-invocation traps); Spring Retry / Boot 4 `@Retryable` (no retries wanted); storing prompts (privacy);
  failing startup without a key (prod would roll back on deploy).
