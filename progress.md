# SeatLock: Progress Log

> Updated at the end of every day (when I say "next"). Keep this file in the repo root and re-upload it to Project knowledge after each update.

## Current Status
- **Current day:** Week 4 · Day 2 (not started, planned Wed Dec 2): Razorpay test account + keys, `RazorpayGateway` (Orders API), Checkout.js in the frontend. Branch `w4-d2` (create only after #7 is squash-merged)
- **Last completed:** Week 4 · Day 1: `PaymentGateway` port + `MockGateway`, V8 payment attempts (one live payment per booking), server-side pricing, `POST /bookings/{publicId}/payment-order`, 20-request race (planned Tue Dec 1, done Wed Nov 18)
- **Live URLs:** Frontend https://seatlock-nu.vercel.app · Backend https://seatlock-prajjwal.duckdns.org · Swagger https://seatlock-prajjwal.duckdns.org/swagger-ui.html · Version https://seatlock-prajjwal.duckdns.org/api/v1/system/version · Grafana —
- **Repo:** https://github.com/prajjwalpathak/SeatLock (public) · CI + Deploy badges in README · `main` protected (ruleset `protect-main`)
- **Hosting:** Azure VM `seatlock-vm` (India South Central, B2als_v2 x64) + Vercel; images from GHCR (ADR-005, #62, #71–#74); decommission after ~2–3 months
- **Git workflow (from W4):** 3 combined tasks per day (2 plan tasks each); each combined task = changes → one verify/test block → one one-line commit + push on the daily branch; day end → PR → squash-merge when CI is green (#9, #75, #116)
- **Carry-overs:** silence Mockito's dynamic-agent warning (W6·D1) · JetBrains student license · IntelliJ vulnerable-dependency scan · delete stale `backend\target\surefire-reports\testsupport.ConcurrencyHarnessIntegrationTest.txt` (from W3·D6, misleading) with `.\mvnw.cmd clean`
- **Schedule:** **~13 days ahead** (W4·D1 done Wed Nov 18 instead of Tue Dec 1); target finish **Mon, Jan 4, 2027**; weeks run Tue–Mon
- **Top risk:** Razorpay Checkout.js + keys (D2); webhooks need a public tunnel (D3/D5); VM burst credits (load tests stay local)

## Decisions Log (ADR summary)
| # | Date | Decision | Why |
|---|---|---|---|
| 1 | 2026-11-10 | Weeks run **Tue–Mon** (Sundays off); finish moves Sat Jan 2 → **Mon Jan 4, 2027** | Plan assumed Nov 10 is a Monday; it's a Tuesday. +2 days, no scope change |
| 2 | 2026-11-10 | GitHub repo is **public** | Free GitHub ARM runners for CI/CD; portfolio visibility |
| 3 | 2026-11-10 | IntelliJ **Ultimate** (trial → student license) | Spring tooling |
| 4 | 2026-11-10 | **Node 26 LTS** locally; Vercel/CI pinned to the same major (fallback 24) | Node 26 became LTS on Oct 28, 2026 |
| 5 | 2026-11-10 | Webhook tunnel: **cloudflared** (not ngrok) | Quick tunnels need no account |
| 6 | 2026-11-10 | App reads repo-root `.env` via `spring.config.import: optional:file:../.env[.properties]` | One source of values for Compose + app; OS env vars override; skipped in Docker/prod |
| 7 | 2026-11-10 | **ADR-001** dependency vulnerability triage; `jackson-databind` overridden → 3.1.8 | CVE-2026-110879 (9.8); fix in same minor line. Logback + test-scope findings accepted, revisit W8·D1 |
| 8 | 2026-11-10 | No Lombok | Records for DTOs; explicit Java for entities |
| 9 | 2026-11-10 | ~~One git commit per day~~ **superseded 2026-11-16 by #75** (commit per task on a daily branch, squash-merged PR) | Clean, day-aligned history |
| 10 | 2026-11-10 | **No interview-angle sections until after v1.0.0**; daily entries list topics covered only | Focus on building now; interview prep after the project |
| 11 | 2026-11-10 | **Hosting:** Oracle Always Free preferred (needs a family Visa/Mastercard credit card); **Azure for Students** ($100, active) as fallback; AWS free plan unavailable (already used). Decide by W2·D4 | No personal credit card; Oracle is free forever and keeps the plan unchanged; Azure lasts ~4–5 months at 24/7 on B2pls_v2 (ARM, 4 GB) |
| 12 | 2026-11-11 | Error contract: RFC 7807 `ProblemDetail` + `code` + `requestId`; 500s never leak internals | One predictable error shape for the frontend; errors traceable in logs |
| 13 | 2026-11-11 | Client `X-Request-Id` accepted only if `[A-Za-z0-9-]{8,64}`, else a UUID is generated | Prevents log injection |
| 14 | 2026-11-11 | A UTC `Clock` bean is injected wherever time is needed (no `Instant.now()`) | Deterministic tests; hold expiry (W3) uses the same clock |
| 15 | 2026-11-11 | Test-only controllers live in package `testsupport` (outside `com.seatlock`) | Never registered by the app's component scan, not even in `@SpringBootTest` |
| 16 | 2026-11-12 | Cross-module references are IDs only (`Booking.userId/showId`, `Event.organizerId`); JPA associations only inside a module | Keeps modules decoupled; other modules' data comes through their public services |
| 17 | 2026-11-12 | **Oversell guard:** partial unique index `ux_booking_seats_live_seat` on `booking_seats (show_id, seat_id) WHERE active`, plus plain `UNIQUE (show_id, seat_id)` on `show_seats` and a composite FK between them | Blocks two live claims on a seat even if Redis or app code fails; released seats can be re-sold and history is kept |
| 18 | 2026-11-12 | Bookings expose a random `public_id` (UUID); internal keys are `bigint` identity | Booking URLs can't be enumerated |
| 19 | 2026-11-12 | Enums stored as `varchar` + `CHECK` (no Postgres `ENUM`); money as `bigint` paise with `CHECK`s | Easy to evolve with Flyway; maps cleanly to `@Enumerated(STRING)` |
| 20 | 2026-11-12 | Persistence tests use `@DataJpaTest` against real Postgres (`replace = NONE`) until Testcontainers on W1·D5 | The guards are Postgres-specific (partial index, composite FK); an in-memory DB can't test them |
| 21 | 2026-11-13 | Demo data in Flyway `V2__seed.sql`, set-based (`generate_series`, data-modifying CTE); show dates relative to the migration date; seed organizers locked (`'!'` hash) with `.test` e-mails | Realistic data that always has upcoming shows; no usable credentials in seeds |
| 22 | 2026-11-13 | Keyset pagination on `(next_show_at, event_id)` with an opaque URL-safe Base64 cursor (full `Instant` precision); bad cursor → 400 `INVALID_CURSOR` | Same speed on every page, stable while data changes; clients don't depend on cursor internals |
| 23 | 2026-11-13 | Read-model queries (event list, upcoming shows, seat map) use `JdbcClient` SQL + `RowMapper`s; JPA entities are for writes | One round trip per query, no N+1; plain SQL is what we `EXPLAIN ANALYZE` |
| 24 | 2026-11-13 | Unpublished events / unbookable shows return 404 (not 403); listings never return past shows (`startsAfter` clamped to now) | Don't reveal drafts; no stale results |
| 25 | 2026-11-13 | Integration tests assert properties computed from the DB (no duplicates, sorted, counts from SQL) instead of hard-coded numbers | Seed dates are relative, so hard-coded counts would break as shows pass |
| 26 | 2026-11-14 | **springdoc-openapi 3.2.0** instead of the plan's 3.0.x (user-approved) | 3.0.x was built against Boot 4.0, and Boot 4.1 removed the APIs deprecated in 4.0; the latest 3.x minor matches Boot 4.1. Still the springdoc 3 / Boot 4 line |
| 27 | 2026-11-14 | Tests run on **Testcontainers** (`pgvector/pgvector:pg16` + `redis:7-alpine`) via `@ServiceConnection`; `@IntegrationTest` meta-annotation; dummy datasource values in `application-test.yml` | Isolated and repeatable, no dependency on the local stack, CI-ready; identical config means one cached context |
| 28 | 2026-11-14 | Full-text search: generated weighted `tsvector` (title A, description B, category/language C; `english` config) + GIN index; `websearch_to_tsquery`; results keep soonest-first order | Always in sync without triggers; never errors on user input; keyset cursor stays valid |
| 29 | 2026-11-14 | Indexes only from measured plans: `show_seats (show_id, price_paise)` and `shows (event_id, starts_at)` (replaces `ix_shows_event`); `show_seats.status` deliberately unindexed | Measured ~5× faster event list; unindexed status keeps hold/booking updates HOT |
| 30 | 2026-11-14 | JaCoCo 0.8.15 report on `verify`; the ≥80% gate is deferred to W6·D1 | 0.8.14+ officially supports Java 25; avoid writing tests just for the number now |
| 31 | 2026-11-14 | Frontend: React 19.3, react-router 8.5, TanStack Query 5.107, Tailwind 4.3 (`@tailwindcss/vite`), Vite 8.3, TypeScript 6.0; `engines.node` 26.x | Current stable versions at scaffold time |
| 32 | 2026-11-14 | Frontend calls the API directly (`VITE_API_BASE_URL`), no dev proxy. CORS allow-list per profile (`seatlock.cors.allowed-origins`, `@NotEmpty`, never `*`), exposes `X-Request-Id`, `allowCredentials(false)` until refresh-token storage is decided (W2·D2) | Local development exercises CORS exactly like prod; missing config fails fast |
| 33 | 2026-11-14 | List filters live in the URL query string; `useInfiniteQuery` drives the keyset cursor; `keepPreviousData`; search submits on Enter (no per-keystroke requests) | Shareable, refresh- and back-button-friendly; no request storm |
| 34 | 2026-11-14 | One `ApiError` for every API failure (status 0 = network); 4xx never retried; `requestId` shown in the UI | Consistent error UX; users can quote a reference that matches server logs |
| 35 | 2026-11-14 | Workflow: Claude explains each frontend change, then writes it directly into `frontend\` (type-checked + linted); backend changes stay manual (paste + learn); npm commands run on Windows only | User's learning focus is the backend; avoids Linux binaries in `node_modules` |
| 36 | 2026-11-14 | **Workflow from Week 2 (supersedes #35):** one task at a time; on "next task" Claude explains and writes the change directly into `backend\` or `frontend\`, then stops; Claude gives a one-line commit message per day, the user commits and pushes; npm/Maven commands still run on Windows | Faster pace; user reviews diffs and verifies locally |
| 37 | 2026-11-14 | **ADR-002:** stateless API (no sessions, CSRF off), our own RS256 JWTs via Spring's Nimbus encoder/decoder, OAuth2 resource-server validation | Works across 2 instances with no shared state; only the private key can sign |
| 38 | 2026-11-14 | Access token 15 min; claims `iss=seatlock`, `aud=seatlock-api`, `sub=<user id>`, `iat`, `nbf`, `exp`, `jti`, `email`, `roles`; decoder: RS256 only, 30 s skew on the app `Clock`, issuer + audience checked; `roles` → `ROLE_*`, `scope` ignored | Rejects alg-confusion and forged tokens; expiry testable; id survives e-mail changes |
| 39 | 2026-11-14 | Keys in `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (base64 on one line); required in prod; tests use a throwaway pair (`allow-ephemeral-keys`); mismatched pair or < 2048 bits fails startup; `kid` = JWK thumbprint | No keys in Git; misconfiguration found at boot, not at first login; rotation possible later |
| 40 | 2026-11-14 | Passwords: `DelegatingPasswordEncoder` with BCrypt only; unknown/locked hashes never match; 8 chars to 72 **bytes**; no composition rules | Algorithm upgradable; no `{noop}`; seed `'!'` can't cause 500s; BCrypt truncates beyond 72 bytes |
| 41 | 2026-11-14 | Security errors rendered by `GlobalExceptionHandler` via `HandlerExceptionResolver`: `AUTHENTICATION_REQUIRED` / `INVALID_TOKEN` / `INVALID_CREDENTIALS` (401), `ACCESS_DENIED` (403; anonymous → 401), `EMAIL_TAKEN` (409) | One error contract; frontend can tell "refresh" from "sign in"; no reasons leaked; unknown e-mail = wrong password |
| 42 | 2026-11-14 | CORS moved to a `CorsConfigurationSource` applied by Spring Security (before auth); `@SecurityRulesSlice` gives web slices the real rules + a mock `JwtDecoder` | Preflights never get 401; slice tests exercise real URL rules |
| 43 | 2026-11-14 | **No refresh tokens** (user decision; plan W2·D2 said "simple refresh token"). Access token TTL 15 min → **8 h**; SPA signs in again on `INVALID_TOKEN` | Simpler; a live demo never logs out mid-way. Trade-off: a stolen token works ≤ 8 h, no early revocation; accepted (no real users/money), recorded in ADR-002 |
| 44 | 2026-11-14 | Method security (`@EnableMethodSecurity`) + `RoleHierarchy` **ADMIN ⊃ ORGANIZER ⊃ USER** (static bean, shared by URL rules) | Role checks live next to the code; admins don't need every role listed |
| 45 | 2026-11-14 | Two gates for role endpoints: URL prefixes (`/api/v1/admin/**` → ADMIN, `/api/v1/organizer/**` → ORGANIZER) **and** class-level `@PreAuthorize` | Defence in depth: a forgotten annotation or a moved controller is still covered |
| 46 | 2026-11-14 | Ownership from the token: `@CurrentUserId` (= JWT `sub`, meta-annotation over `@AuthenticationPrincipal`); never from request params | Organizers can't read/change others' events; other modules don't touch JWT classes |
| 47 | 2026-11-14 | Public demo logins in Flyway `V5` (`demo.user@seatlock.test`, unlocked `organizer.cinema@seatlock.test`, password `seatlock-demo`); **ADMIN bootstrapped from `SEATLOCK_ADMIN_EMAIL`/`PASSWORD`** at startup: create-only, 12+ chars, never promotes an existing account, race-safe | Interviewers can try the app; admin credentials never in a public repo; no account takeover via a pre-registered admin e-mail |
| 48 | 2026-11-14 | Admin lists use offset `PageResponse` (own record, size ≤ 100); public lists keep keyset `CursorPage`. `SecurityMatrixIntegrationTest` (real users + real RS256 tokens) is the access contract: one row per caller × protected endpoint | Spring's `Page` JSON isn't a stable contract; matrix fails loudly if a rule is loosened |
| 49 | 2026-11-14 | **ADR-003:** Google sign-in with Spring OAuth2 Client in a **separate filter chain** (`@Order(1)`, `/oauth2/**` + `/login/oauth2/**`); API chain `@Order(2)` unchanged | Browser-redirect flow kept apart from the token-only API |
| 50 | 2026-11-14 | In-flight OAuth request in **Redis** (5 min, GETDEL, JSON fields, no Java serialization) + `SL_OAUTH2_STATE` binding cookie (HttpOnly, Lax, `/login/oauth2/`); **PKCE S256**; Google tokens discarded | No session → works across 2 instances; blocks login CSRF and replay |
| 51 | 2026-11-14 | Token handoff **A2**: one-time code (256-bit, `SHA-256` stored, 60 s, single use) → `POST /api/v1/auth/oauth/exchange` → our JWT; `Referrer-Policy: no-referrer` | Our JWT never in a URL, history or log (user-approved) |
| 52 | 2026-11-14 | Linking **B1**: only `email_verified = true`; existing account (any provider) signed into unchanged; else new USER (`GOOGLE`, no password); failures → `{frontend}/login?error=CODE`; redirect base fixed by `seatlock.frontend.base-url` | No takeover via unverified e-mail; no open redirect (user-approved) |
| 53 | 2026-11-14 | Frontend auth: token in localStorage; API client adds Bearer, on `INVALID_TOKEN` signs out and retries once without token; `AuthProvider` (`/me`, expiry timer, cross-tab sync); all user data under query key `['me', …]`, dropped on sign-in/out; `RequireAuth` (UX only) | Public pages survive an expired token; no stale data between users |
| 54 | 2026-11-14 | **Hosting: Azure for Students** (ARM VM, e.g. B2pls_v2, 4 GB) replaces Oracle Always Free (user decision; Oracle signup not possible) | Active student credit; same Docker/Nginx/DuckDNS plan; budget alert guards the $100 credit |
| 55 | 2026-11-14 | Create-show prices: **one price per section** (PLATINUM/GOLD/SILVER), every seat of the section gets it (user-approved) | Matches the seed data; simple organizer UX |
| 56 | 2026-11-15 | **ADR-004:** Spring Cache on Redis (own `RedisCacheManager`, typed JSON per cache, SCAN clears, statistics, error handler = miss, 500 ms timeout, `SEATLOCK_CACHE_ENABLED` kill switch). Cached: `eventDetail` 5 min, `eventSearch` 60 s. **Seat map not cached** (single query measured faster) | Kept only what benchmarks proved: detail p95 −32%, list p95 −21%; seat-map cache +59% → removed |
| 57 | 2026-11-15 | Cache hits never touch the DB: `@EnableCaching(order = HIGHEST_PRECEDENCE)`, read services not `@Transactional`; misses load in `CatalogReadCache` (read-only tx) | Run 1 showed hits still paid for a transaction; test asserts no connection borrowed on hits |
| 58 | 2026-11-15 | Eviction **after commit**: `ShowScheduledEvent` → `@TransactionalEventListener(AFTER_COMMIT)` evicts `eventDetail::{id}` + clears `eventSearch` (Observer) | No re-caching of pre-commit data; rollback evicts nothing; same event drives live updates on W5 |
| 59 | 2026-11-15 | **Venue no-overlap guard:** `shows.ends_at` (start + duration + 30 min) + exclusion constraint `ex_shows_venue_no_overlap` (`btree_gist`, `tstzrange '[)'`, scheduled only) → 409 `SHOW_OVERLAP` | DB-enforced like the seat guard; proven with a 2-thread race test |
| 60 | 2026-11-15 | `POST /api/v1/organizer/events/{id}/shows`: owner only (else 404), not cancelled, start 1 h–1 y ahead, prices for exactly the venue's sections, show + all seats in one transaction, one `INSERT … SELECT … JOIN (VALUES …)` | No half-created shows; no IDOR; no per-seat inserts |
| 61 | 2026-11-15 | Benchmark method: variants measured against the **original code** (git worktree), INFO logging, 200 warm-up + 500 requests, **median of 3 runs in one session**; benchmark hit rates are not production hit rates. `/actuator/**` (metrics, caches) ADMIN-only, exposed locally only | Run 1 was invalid (wrong baseline, DEBUG noise, single runs); session-to-session noise is large on the laptop |
| 62 | 2026-11-16 | **ADR-005 hosting:** Azure VM in **India South Central**, **Standard_B2als_v2 (x64, 2 vCPU, 4 GB)**, Ubuntu 24.04, Standard static IP + DuckDNS; frontend on Vercel. Supersedes the ARM size in #54 | Student-subscription policy allows only 5 regions (Central India blocked); B2pls_v2 (ARM) `NotAvailableForSubscription`; x64 also avoids ARM emulation in CI. Container Apps/Render/laptop-tunnel compared and rejected |
| 63 | 2026-11-16 | Backend image: multi-stage `eclipse-temurin:25-jdk` → `25-jre` (Ubuntu, glibc), layered jar (`-Djarmode=tools extract --layers`), non-root `spring`, exec-form `java -jar`, `MaxRAMPercentage=70`, `ExitOnOutOfMemoryError`, UTC, `HEALTHCHECK` on liveness; tests not run in the image build | Small runtime, fast rebuilds (only the app layer changes), graceful SIGTERM; Testcontainers needs Docker, so CI runs tests before building |
| 64 | 2026-11-16 | Prod = one Compose project (`infra/docker-compose.prod.yml`): only Nginx publishes 80/443; app/Postgres/Redis private; `restart: unless-stopped`; memory limits (app 1200m, PG 768m, Redis 256m); Docker logs 3×10 MB; Redis without persistence; 2 GB swap on the VM | Minimal attack surface on a public VM; survives reboots; can't fill the disk |
| 65 | 2026-11-16 | Edge: Nginx 1.28, Let's Encrypt (first issue `--standalone`, renew via webroot loop every 12 h, Nginx reload every 6 h), TLS 1.2/1.3, HSTS, gzip, **10 req/min/IP (burst 5) on login/register → 429**, `/actuator/*` hidden except health, Docker DNS resolver for `app`. App: `forward-headers-strategy: native`, health probes, readiness = `readinessState,db,redis` | HTTPS-correct OAuth redirects; brute-force guard; redeploys never leave Nginx on a stale IP; liveness/readiness separated (no restart loops on DB blips) |
| 66 | 2026-11-16 | Prod secrets generated **on the VM** by `infra/scripts/init-env-prod.sh` (new RS256 pair, DB password, admin password printed once) into `infra/.env.prod` (mode 600, git-ignored) | Secrets never pass through chat, Git or the laptop; prod keys differ from local |
| 67 | 2026-11-16 | Vercel project imported from GitHub (root `frontend`, `VITE_API_BASE_URL` at build time), `frontend/vercel.json` SPA rewrite; prod origin `https://seatlock-nu.vercel.app` set as `FRONTEND_BASE_URL` + `CORS_ALLOWED_ORIGINS` | Auto-deploy on push; deep links and `/auth/callback` survive refresh; CORS stays an exact allow-list |
| 68 | 2026-11-16 | Until W2·D6, code reaches the VM via `tar` + `scp` (backend + infra only) and is built there | Keeps one commit per day; Day 6 switches to CI-built images from GHCR |
| 69 | 2026-11-16 | Load tests/benchmarks stay **local**; the VM is decommissioned (resource group deleted) after ~2–3 months | B-series burst credits make VM numbers unreliable; honest resume line: deployed, then decommissioned to save cost |
| 70 | 2026-11-16 | `spring.data.redis.repositories.enabled: false` | Redis is cache + plain keys; removes repository-scan noise at startup |
| 71 | 2026-11-16 | **CI** (`ci.yml`): push to `main` + every PR; backend `mvnw verify` (Testcontainers on GitHub's Docker, JaCoCo summary + report artifact, surefire reports on failure), frontend `npm ci`/lint/build on Node 26; `contents: read`; concurrency cancels superseded runs | Every change tested on a clean machine; coverage visible per run |
| 72 | 2026-11-16 | **Deploy** (`deploy.yml`) via `workflow_run` on CI **success** on `main` (checks out the tested SHA): image built once (`linux/amd64`, BuildKit `gha` cache), pushed to **GHCR** as `sha-<12>` + `latest` with `GITHUB_TOKEN` (`packages: write`); package public; `concurrency: deploy-production` (no cancel) | Build once, deploy the tested artifact; immutable tags make rollback trivial; no personal tokens |
| 73 | 2026-11-16 | **SSH push-deploy (option A, user-approved):** NSG SSH open to Any + key-only sshd + **fail2ban**; CI key generated on the VM, pinned in `authorized_keys` with `command="…/deploy.sh",restrict`; host key pinned (`StrictHostKeyChecking=yes`); secrets `DEPLOY_HOST`/`DEPLOY_SSH_KEY`/`DEPLOY_KNOWN_HOSTS`. Rejected: VM pull loop (no feedback), Azure run-command/OIDC (student tenant), Tailscale (extra account) | Real deploy result in Actions; a leaked CI key can only redeploy |
| 74 | 2026-11-16 | `deploy.sh`: validates `sha-<12 hex>`/`latest`, `flock`, pull → `APP_IMAGE` in `.env.prod` → `up -d --no-deps app` → wait healthy (~2.5 min) → else logs + **automatic rollback**. Image carries `GIT_SHA` → `GET /api/v1/system/version`; CI smoke test requires readiness UP **and** the deployed revision. Manual `workflow_dispatch` `tag` = one-click rollback | A broken build can't take the site down; "deployed" means the new commit is actually serving |
| 75 | 2026-11-16 | **Git workflow (supersedes #9, user-approved option B):** a commit per task on a daily branch (`w3-d1`, …), PR at day end, **squash merge** once CI is green; ruleset `protect-main`: PR required (0 approvals), required checks `Backend (build, test, coverage)` + `Frontend (lint, build)`, linear history, no force-push, no deletion; head branches auto-deleted | Real quality gate on the deployed branch; still one squashed commit per day on `main` |
| 76 | 2026-11-16 | Cache evictions use **immediate** semantics: `evictIfPresent()` / `invalidate()` (never `evict()`/`clear()`, which the `Cache` contract allows to be deferred and Spring Data Redis 4 defers) in `CatalogCacheEvictor` and tests | Deferred deletes raced with fresh writes (found by CI: keys vanished after put); after-commit eviction must be visible immediately |
| 77 | 2026-11-16 | Hold keys `hold:show:{showId}:seat:{seatId}` → `holdId`, **TTL = the hold** (`HoldKeys`); `SeatHoldReader` reads a whole show with **one `MGET`**; port `SeatHoldLookup` defined in catalog, implemented in booking | One command, one round trip (vs N `GET`s or `SCAN`); expiry needs no sweeper; dependencies run booking → catalog only (no cycle when holds validate seats on D2) |
| 78 | 2026-11-16 | Seat-map merge: Postgres **BOOKED always wins**; Redis can only turn AVAILABLE into HELD; only AVAILABLE seats are looked up | DB is durable, Redis ephemeral; a stale hold key can never hide a booking |
| 79 | 2026-11-16 | Redis unreachable → **200 with DB statuses + `holdsLive: false`** (UI banner), never a 500; seat-map service **not `@Transactional`**; `Cache-Control: no-store` | Seat map must not fail because of holds; no DB connection held while waiting on Redis; live data must not be reused by browsers/proxies. Showing AVAILABLE during an outage can't oversell (hold script + partial unique index) |
| 80 | 2026-11-16 | Lettuce **`DisconnectedBehavior.REJECT_COMMANDS`** via `LettuceClientOptionsBuilderCustomizer` (`RedisClientConfig`); auto-reconnect on | Default queues commands while reconnecting → every request waited the 500 ms timeout (measured 512 ms); now ~5 ms (FAILURES.md F1). A hung-but-open socket is still bounded only by the timeout |
| 81 | 2026-11-16 | Seat-map UI: route `/shows/:showId`; selection is client-only (max 6), **derived from the latest map** (taken seats drop out); `staleTime: 0` + refetch on focus + Refresh button; no `sessionStorage` persistence | Holds (D2 API, D5 UI) make selections durable; no effect-based state sync; WebSocket push replaces polling on W5 |
| 82 | 2026-11-16 | Benchmarks compare variants **within one session** and report the median per session (B0 `main` via worktree, B1/B2 overlay, B3 Redis down); claim "no regression", not "faster" | Overlay faster than baseline in both sessions, but the likely cause (dropped read-only transaction) wasn't isolated |
| 83 | 2026-11-16 | Holds: **TTL 8 min, max 6 seats, one active hold per user per show** (`seatlock.holds.*`, `@Validated`; user-approved defaults) | Long enough for checkout (W4), short enough that abandoned seats return; one hold per user stops seat hoarding |
| 84 | 2026-11-16 | **`hold_seats.lua`**: user key checked → all seat keys checked → all keys set with one TTL (seat keys, `hold:user:{u}:show:{s}`, hash `hold:id:{holdId}`); returns `OK` / `HOLD_EXISTS` / `SEATS_TAKEN` + positions, writing nothing on failure. **`release_seats.lua`** deletes only keys still equal to the `holdId` | Atomic all-or-nothing without locks; holds expire together with no job; an expired hold can't free someone else's seat |
| 85 | 2026-11-16 | `HoldService` order: input checks → DB checks via catalog's public `findHoldableSeats` (bookable, not started, seats in show, not BOOKED) → one Redis call; prices from the DB; not `@Transactional` | Cheap rejections first; client can't set prices; no connection held across Redis; booking → catalog only |
| 86 | 2026-11-16 | Hold API: `POST /api/v1/shows/{id}/holds` (201 + `Location`, `no-store`), `GET`/`DELETE /api/v1/holds/{holdId}` owner-only (else 404), `holdId` must be a UUID; 409 `SEATS_UNAVAILABLE` carries `seatIds` via `SeatLockException.getProperties()`; Redis down → **503 `HOLDS_UNAVAILABLE` + `Retry-After: 5`** | UI can mark exactly the taken seats; no probing other users' holds; no arbitrary Redis keys; `common` doesn't depend on `booking` |
| 87 | 2026-11-16 | **ADR-006:** Redis Lua hold + DB partial unique index as the final guard; optimistic locking kept only as the W4·D6 benchmark strategy; per-seat `SETNX` and `FOR UPDATE` rejected | Redis prevents conflicts cheaply; Postgres makes oversells impossible even if Redis loses data (no persistence in prod) |
| 88 | 2026-11-16 | Cache writer `RedisCacheWriter.create(factory, c -> c.batchStrategy(scan(1000)).immediateWrites())` (**root-cause fix behind #76**) | Spring Data Redis 4 writes cache entries asynchronously by default; puts/evictions must be visible immediately (FAILURES.md F2) |
| 89 | 2026-11-16 | Race tests run through the real `HoldService` with fixed thread pools released by a `CountDownLatch`, each `@RepeatedTest(5)`; every outcome collected (unexpected exceptions fail) | Real interleaving, repeated to beat luck; no swallowed errors. 200-thread HTTP version on W3·D6 |
| 90 | 2026-11-16 | **`Idempotency-Key` required** on `POST /holds` (8–64 chars `[A-Za-z0-9_-]`), responses kept **24 h** (user-approved defaults) | Retries/double-clicks get their original result; a missing key is a client bug, not a silent duplicate |
| 91 | 2026-11-16 | **ADR-007:** keys in Postgres `idempotency_keys` (V7), `UNIQUE (user_id, idem_key)`, claim = `INSERT … ON CONFLICT DO NOTHING RETURNING id`, every store call auto-committed | Unique constraint decides races across instances; durable (Redis has no persistence in prod, #64); claim visible immediately |
| 92 | 2026-11-16 | Fingerprint SHA-256 of `METHOD path` + re-serialized `@RequestBody` → mismatch **422 `IDEMPOTENCY_KEY_REUSED`**; only **2xx** stored and replayed (status + body, `Idempotent-Replayed: true`, `no-store`); any exception **releases** the key; duplicate while running → 409 `IDEMPOTENCY_IN_PROGRESS` | Whitespace-insensitive match; holds fail without side effects, so re-running a failure is safe; headers (Location) not replayed |
| 93 | 2026-11-16 | Crash recovery: **30 s lease** (`locked_at`) + compare-and-set takeover; `HOLD_EXISTS` now carries `holdId`; if storing the response fails after success the key is **not** released | A crashed request can't block its key for 24 h; the re-run answers with the existing hold; never pretend a done operation didn't happen |
| 94 | 2026-11-16 | Interceptor wired as a plain Spring AOP `Advisor` (`@Idempotent` pointcut, `ROLE_INFRASTRUCTURE`, lowest precedence → after `@PreAuthorize`), interceptor looked up via `ObjectProvider`; header documented by an `OperationCustomizer`; 422 = `HttpStatus.UNPROCESSABLE_CONTENT` | No AspectJ dependency; unauthorized calls never touch the table; `@Lazy` can't proxy a record (final) |
| 95 | 2026-11-16 | `SchedulingConfig` (`@EnableScheduling`, `seatlock.scheduling.enabled`, **false in the test profile**); `IdempotencyCleanupJob` hourly (`PT1H`, first run after 1 min), no distributed lock | Jobs never interfere with tests; DELETE of expired rows is idempotent on two instances |
| 96 | 2026-11-16 | Git: create the next day's branch **only after** the previous PR is squash-merged; if branched early, `git rebase --onto origin/main <last commit of the old day>` before the PR | Squash merges rewrite history: a branch carrying the old day's commits conflicts on every shared file (happened on W3·D2) |
| 97 | 2026-11-17 | Workflow: Claude **always picks the recommended option** after a Council review and decides what's feasible, instead of asking (user preference from W3·D4); asks only for scope/stack changes or irreversible steps | Faster days; decisions still recorded here and in ADRs |
| 98 | 2026-11-17 | **ADR-008:** booking lifecycle as a sealed **State pattern** (`BookingState`); invalid moves → 409 `INVALID_BOOKING_TRANSITION` (status + action); no `HELD → CONFIRMED`; terminal states final; expiry refused before `expiresAt` | Rules live in one place per state; a duplicate webhook can't confirm twice |
| 99 | 2026-11-17 | Hold → booking at **"Proceed to pay"** (`POST /api/v1/holds/{holdId}/booking`), booking keeps the hold's `expiresAt`; one transaction: `show_seats` AVAILABLE→HELD compare-and-set + booking + `booking_seats` via `saveAndFlush`; Redis hold released after commit (user-approved defaults) | Redis for contention, Postgres for commitment; the partial unique index fires inside the transaction |
| 100 | 2026-11-17 | Conversion is **naturally idempotent** (`hold_id` UNIQUE → 201 new / 200 existing, no Idempotency-Key); every lost race (hold released, seats already HELD, `uq_bookings_hold_id`) re-checks for the winner's booking | Retries and double-clicks get the same booking instead of 404/409 |
| 101 | 2026-11-17 | Booking changes seat statuses only via catalog's **`ShowSeatStatusService`** (`markHeld` / `markAvailable`, conditional UPDATE, joins the caller's transaction); seats released by **deactivating** `booking_seats` | Module rule (#16); the index frees seats for resale while history stays |
| 102 | 2026-11-17 | **Expiry sweeper** every 30 s, batches of 100 (max 20/run), `FOR UPDATE SKIP LOCKED`, `expire()` via the state machine; user actions (cancel) take `PESSIMISTIC_WRITE`; cancel of a cancelled booking → 200; no sweeper index until measured (W6·D5) | Parallel sweepers split rows and never block; cancel waits for a sweeper's commit then answers correctly |
| 103 | 2026-11-17 | `@EntityGraph("seats")` on booking finders (open-in-view off); hold `expiresAt` truncated to **milliseconds** | No LazyInitializationException outside a session; POST/GET hold and the booking report the same instant |
| 104 | 2026-11-17 | **ADR-009:** rate limiting with **Bucket4j 8.22.0** (`bucket4j_jdk17-lettuce`, buckets in Redis via CAS) on the app's own Lettuce 7 client; policy `holds`: per IP 30/min, per user burst 5 + 10/min → 429 `RATE_LIMITED` + `Retry-After` (+ `RateLimit-Remaining`) | Shared across instances; locked stack; Lettuce 7 compatibility proven by a real-Redis test |
| 105 | 2026-11-17 | `@RateLimited("policy")` + `HandlerInterceptor` after Spring Security; registered by a `@Bean WebMvcConfigurer` (not a WebMvcConfigurer class) and logs `Rate limiting enabled/DISABLED` at startup | Absent from `@WebMvcTest` slices, always present in full contexts; switch state visible (F3) |
| 106 | 2026-11-17 | `RateLimiter` **fails open**; on a failure it drops the cached proxy and **retries once** on a fresh one; it's a `SmartLifecycle` that drops the proxy on stop | Protection, not correctness (holds 503 themselves); a stale connection after a client restart heals on the next call |
| 107 | 2026-11-17 | Test profile: per-IP limit 100000, per-user 20/min | The whole suite runs from 127.0.0.1; real behaviour checked with small limits in `RateLimiterIntegrationTest` |
| 108 | 2026-11-17 | Frontend: **one `Idempotency-Key` per user attempt**, automatic retries (network/503/`IDEMPOTENCY_IN_PROGRESS`, max 2) reuse it; `ApiError.details` (seatIds, holdId) + `retryAfterSeconds`; countdowns on the **server clock** (`/system/time` offset) | A flaky network can't create a second hold; precise UI reactions; laptop clock skew can't fake expiry |
| 109 | 2026-11-17 | Seat map hold flow: green hold panel (countdown, Release, Proceed to pay), own seats "✓ Your hold", map read-only while holding or after start; hold remembered per show in `sessionStorage` (F5 resumes; 404 → forgotten; `HOLD_EXISTS` resumes too); booking page polls every 10 s while unpaid and shows Expired at 0:00 before the sweeper runs | Holds survive refreshes; every server error has a specific, honest message |
| 110 | 2026-11-17 | **Git rule, enforced:** never resolve squash-merge conflicts with "accept incoming/current"; stop and rebase (`git rebase --onto origin/main <old day's last commit>`); next day's branch only after the merge (#96) | "Accept incoming" silently dropped 99 lines incl. the rate-limit config (FAILURES F3) |
| 111 | 2026-11-18 | Concurrency proof = 3 races: **A** 200 users, 1 seat over real HTTP; **B** 200 users, random overlapping 1–4 seats in a 12-seat block, then all winners book at once; **C** 200 direct DB transactions on one seat with Redis bypassed | Covers the single-seat case, realistic overlap end to end, and the last line of defence on its own |
| 112 | 2026-11-18 | Races run on a **real Tomcat port** (`@RealServerTest`, `RANDOM_PORT`) with a start-gate harness (`ConcurrencyHarness`, one platform thread per request); a request without an HTTP response fails the test | MockMvc bypasses sockets and Tomcat's pool; dropped requests must never count as a pass |
| 113 | 2026-11-18 | Race context only: `server.tomcat.accept-count=512` + one unasserted warm-up race; overlap asserted as `peakInFlight` ≥ 10% | A 200-connect burst overflowed the default backlog of 100 on a cold server (Windows resets the extras); warm servers answer early requests before late threads wake |
| 114 | 2026-11-18 | Race B seeds its random seat picks and logs the seed; invariant SQL `DOUBLE_SOLD_SEATS` (live claims per seat > 1) must return 0 rows | Any failing interleaving can be replayed; the DB, not status codes, is the final word |
| 115 | 2026-11-18 | Never use the section-sign character in docs/code; existing ones replaced with "section" except the applied `V4` migration comment | Readability preference; editing an applied migration changes its Flyway checksum |
| 116 | 2026-11-18 | **Workflow from Week 4 (refines #36/#75):** each day's 6 plan tasks are paired into **3 combined tasks**; each pair gets one PowerShell verify/test block and one commit (pushed to the daily branch); at day end one PR, squash-merged once CI is green. Pace unchanged (~6 h/day) | Fewer round trips and commits per day; same scope and timeline |
| 117 | 2026-11-18 | **ADR-010:** `PaymentGateway` port (Adapter/Strategy); `MockGateway` (in-process, `order_mock_` + 14 chars) is the default for local, tests and load tests; `seatlock.payments.gateway=mock\|razorpay` builds exactly one bean, logged at startup; `razorpay` fails startup until D2 | No real provider in tests/k6; adding a provider is one adapter |
| 118 | 2026-11-18 | **V8 payments** = one row per attempt (`attempt` 1..n, CREATED/CAPTURED/FAILED/REFUNDED, timestamps tied by CHECKs); `UNIQUE (gateway, gateway_order_id)`, `UNIQUE (booking_id, attempt)`; partial unique **`ux_payments_live_booking` WHERE status IN (CREATED, CAPTURED)** | Retries keep history; the DB guarantees one live payment per booking whatever the code does |
| 119 | 2026-11-18 | **Server-side amount:** payment-order has no body; `BookingPricing` = sum of the booking's live seat prices, must equal `total_paise` (mismatch → 500, nothing charged); gateway echo must match | Client can't tamper with the price; corrupted data is refused, never guessed |
| 120 | 2026-11-18 | Create-order flow: read-only checks → open order? return it (200) → **gateway call outside any transaction** → short tx: `BookingService.startPayment` (`MANDATORY`, `PESSIMISTIC_WRITE`, re-check, HELD → PAYMENT_PENDING) → open order now? return it (ours left unused) → insert (201). Naturally idempotent, no Idempotency-Key | No DB connection/lock held across a network call (#79); concurrent clicks serialize on the booking row; unused provider orders cost nothing |
| 121 | 2026-11-18 | Payment rules: deadline checked on the app clock (409 **`BOOKING_EXPIRED`** even before the sweeper runs); CANCELLED/CONFIRMED/REFUNDED → 409 `INVALID_BOOKING_TRANSITION` (`requirePayable`); PAYMENT_PENDING may start a new attempt after a FAILED one; `expiresAt` is **not** extended (late payments refunded W4·D4); receipt = public id without dashes + `-attempt` (≤ 40) | Clear, testable rules; matches Razorpay's receipt limit |
| 122 | 2026-11-18 | Payment module talks to booking only via `BookingService.checkPayable` / `startPayment` and the `PayableBooking` record (internal id only for the FK); dependencies payment → booking → catalog | Module rule (#16) with no cycles |

## Metrics Captured (resume numbers)
| Metric | Value | How measured | Date |
|---|---|---|---|
| Event list query (Q1) after targeted index | 3.31 → **0.66 ms (~5×)**; buffers 251 → **121 (−52%)** | `EXPLAIN (ANALYZE, BUFFERS)`, seeded DB, warm cache (`docs/explain/`) | 2026-11-14 |
| Event detail shows query (Q2) | 1.11 → **0.15 ms**; buffers 24 → 10 | same | 2026-11-14 |
| Test coverage (JaCoCo) | 79% instructions, ~80% lines (309/384), 60% branches; 33 tests | `mvnw verify` | 2026-11-14 |
| Event detail latency, Redis cache vs original code | p50 4.46 → **2.77 ms (−38%)**; p95 5.33 → **3.61 ms (−32%)**; p99 6.52 → 4.39 ms | `load-tests/bench-catalog.ps1`, 1 client, 500 req after 200 warm-up, median of 3 runs, same session (`docs/benchmarks.md` Run 2) | 2026-11-15 |
| Event list latency, Redis cache vs original code | p50 5.35 → **4.16 ms (−22%)**; p95 6.46 → **5.12 ms (−21%)**; p99 8.34 → 6.14 ms | same | 2026-11-15 |
| Seat map: cached layout/prices vs single query | p95 7.72 → 12.29 ms (**+59%, rejected**; kept the single indexed query) | same | 2026-11-15 |
| Test coverage (JaCoCo) | **92.5% instructions, 92.1% lines, 73.1% branches**; 173 tests | `mvnw verify` (`target/site/jacoco/jacoco.csv`) | 2026-11-16 |
| CI pipeline | CI ~5–8 min first run (cached later); image build ~1 min with BuildKit cache; deploy + health-wait < 2.5 min | GitHub Actions run times | 2026-11-16 |
| Container startup (prod profile) | **~9.0 s** to `Started`; image content 194 MB | `docker run` on the laptop (Docker Desktop) | 2026-11-16 |
| Seat map with live hold overlay vs W2·D6 code | p50 6.00 → **5.41 ms**, p95 6.87 → **6.36 ms** (session 1); 6.25 → 5.30 / 9.62 → 6.19 (session 2): **no measurable overlay cost**; 50 holds ≈ 0 holds | `bench-catalog.ps1 -Only seats -ShowId 34`, 500 req after 200 warm-up, medians per session (`docs/benchmarks.md` section 2) | 2026-11-16 |
| Seat map with Redis down | **~512 ms → ~5 ms** per request (Lettuce `REJECT_COMMANDS`); recovery ~10 ms within 3 s of Redis restart | `docker stop seatlock-redis` drill + B3 benchmark (`docs/FAILURES.md` F1) | 2026-11-16 |
| Tests | 187 (174 + 13 new) | `mvnw verify` / CI | 2026-11-16 |
| Hold races (smoke) | 20 threads × 5 runs: **exactly 1 winner** per seat; overlapping multi-seat holds never share a seat, no partial writes; one user racing → 1 hold | `HoldRaceIntegrationTest` (Testcontainers) | 2026-11-16 |
| Tests | 234 (187 + 47 new) | `mvnw verify` | 2026-11-16 |
| Idempotent holds | 20 concurrent claims of one key → **1 winner**; 10 concurrent identical `POST /holds` → **1 hold** (rest replay or 409) | `IdempotencyStoreIntegrationTest`, `HoldIdempotencyIntegrationTest` | 2026-11-16 |
| Tests | 259 (234 + 25 new) | `mvnw verify` | 2026-11-16 |
| Booking races | 5 concurrent conversions of one hold → **1 booking**; 2 sweepers × 30 expired bookings → **each expired once**; locked row skipped in < 5 s | `BookingConversionIntegrationTest`, `BookingExpiryIntegrationTest` | 2026-11-17 |
| Tests | 313 (259 + 54 new) | `mvnw verify` | 2026-11-17 |
| Rate limiting | burst/refill/independent keys verified against real Redis (Lettuce 7); 21st hold in a minute → **429** + Retry-After | `RateLimiterIntegrationTest`, `HoldRateLimitIntegrationTest` | 2026-11-17 |
| Tests | 319 (313 + 6 new) | `mvnw verify` / CI | 2026-11-17 |
| Concurrency (race A) | 200 users, 1 seat, real HTTP: **exactly 1 winner, 199 × 409**, 3/3 runs; warm p50 0.7–1.2 s, p99 1.8–2.3 s (cold p50 ~7 s) | `HoldRace200IntegrationTest` | 2026-11-18 |
| Concurrency (race B) | 200 users, overlapping seats, 12-seat block → 7–8 winners, **12/12 sold, oversells = 0**, 3/3 runs | `OverlappingHoldsRaceIntegrationTest` | 2026-11-18 |
| Concurrency (race C) | Redis bypassed, 200 DB transactions on one seat → **1 commit, 199 × 23505** (unique index), 248 ms | `LiveSeatIndexRaceIntegrationTest` | 2026-11-18 |
| Tests | 327 (319 + 8 new incl. repetitions) | `mvnw verify` / CI | 2026-11-18 |
| Payment-order race | 20 concurrent "Pay now" for one booking over real HTTP → **1 × 201, 19 × 200, one order id, one payment row**, 5/5 runs | `PaymentOrderRaceIntegrationTest` | 2026-11-18 |
| Tests | 364 (327 + 37 new incl. repetitions) | `mvnw verify` / CI | 2026-11-18 |

## Accounts & Config (no secrets here, only where they live)
| Item | Status | Where stored |
|---|---|---|
| GitHub repo | ✅ Created (public) | github.com/prajjwalpathak/SeatLock |
| Oracle Cloud VM | ❌ Not used (hosting: Azure, #54/#62) | — |
| Azure for Students | ✅ Active ($100, 12 months; **production host**: `seatlock-vm` in RG `seatlock-vm_group`, India South Central; budget alert) | portal.azure.com (DTU email); SSH key `~\\.ssh\\azure_seatlock`; prod secrets in `~/seatlock/infra/.env.prod` on the VM |
| AWS | ❌ Free plan already used up; not used for this project | — |
| DuckDNS domain | ✅ `seatlock-prajjwal.duckdns.org` → VM static IP | duckdns.org (token in DuckDNS account) |
| Razorpay test keys | ⏳ create test-mode account + keys before W4·D2 (don't paste them in chat) | `.env` (git-ignored) |
| Google OAuth client | ✅ Web client "SeatLock local" (Testing mode, test user added); redirect `http://localhost:8080/login/oauth2/code/google`; prod URI `https://seatlock-prajjwal.duckdns.org/login/oauth2/code/google` added W2·D5 | `.env` (`GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`, git-ignored) |
| Gemini/Groq API key | — (W7·D1) | `.env` (git-ignored) |
| Vercel project | ✅ imported from GitHub (root `frontend`), `https://seatlock-nu.vercel.app`; env `VITE_API_BASE_URL` | vercel.com (GitHub login) |
| Local Postgres/Redis creds | ✅ | `.env` (git-ignored); keys documented in `.env.example` |
| Local JWT keys + bootstrap admin | ✅ generated on W2·D1 / W2·D2 | `.env` (`JWT_*`, `SEATLOCK_ADMIN_*`, git-ignored) |

---

## Daily Entries

<!-- Template for each day (copy, then fill in):

### Week N · Day M: <title> (<date>)
**Status:** ✅ Done / ⚠️ Partial
**Built:**
- …
**Files created/changed:**
- …
**Decisions:**
- …
**Metrics recorded:**
- …
**Problems & fixes:**
- …
**Carry-over:**
- …
**Interview topics covered (Q&A deferred until after v1.0.0):**
- …
**Commit:** `<type>(<scope>): <summary>`

-->

### Week 1 · Day 1: Setup & first boot (Tue, Nov 10, 2026)
**Status:** ✅ Done
**Built:**
- Installed WSL 2, Temurin JDK 25, Git, Docker Desktop, IntelliJ IDEA; set JAVA_HOME and Git config
- Created public GitHub repo + folder skeleton per the plan
- Generated Spring Boot **4.1.2** / Java 25 project (webmvc, validation, data-jpa, flyway, data-redis, actuator)
- Local stack via Docker Compose: Postgres 16 (pgvector image) + Redis 7, healthchecks, localhost-only ports
- App boots; `/actuator/health` **UP** with `db` (PostgreSQL) and `redis` (7.4.12); generated context test passes
**Files created/changed:**
- `.gitattributes`, `.gitignore`, `README.md`, `.env.example`
- `infra\docker-compose.local.yml`; `.gitkeep` in `infra\*`, `load-tests`, `docs\decisions`, `.github\workflows`
- `backend\` (Initializr project), `backend\pom.xml` (metadata tidy + Jackson override), `backend\src\main\resources\application.yml`
- `docs\decisions\ADR-001-dependency-vulnerability-triage.md`
**Decisions:**
- See Decisions Log #1–#10
**Metrics recorded:**
- None (infrastructure only)
**Problems & fixes:**
- `docker` → "cannot find dockerDesktopLinuxEngine pipe": Docker Desktop engine wasn't running → started it, enabled "Start Docker Desktop when you sign in"
- `psql` → "database system is starting up": ran too early during first-boot initdb → wait for `(healthy)`
- Port 5432 taken by a native Postgres → `POSTGRES_PORT=5433` in local `.env` only
- IntelliJ flagged 5 CVEs → triaged per ADR-001 (Jackson overridden; logback not applicable; 3 test-scope only)
- Initializr left empty `<name/>`, `<scm>`, etc. placeholders in pom → removed
**Carry-over:**
- Create Oracle Cloud account this week (ARM capacity can take time); apply for JetBrains student license
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Maven Wrapper; `@SpringBootApplication` / auto-configuration / starters; Boot 4 modular starters
- Docker named volumes vs bind mounts; healthchecks (running vs healthy); localhost port binding, Docker bypassing UFW
- Secrets: `.env` vs `.env.example`, rotate-on-leak; `.gitattributes` vs `core.autocrlf`
- Transitive CVE triage and BOM version-property overrides; Actuator health indicators
**Commit:** `chore(infra): bootstrap repo, local stack and Spring Boot 4.1 backend`

### Week 1 · Day 2: App skeleton (Wed, Nov 11, 2026)
**Status:** ✅ Done
**Built:**
- Package-by-feature skeleton: `common`, `identity`, `catalog`, `booking`, `payment`, `realtime`, `assistant` (each with `package-info.java`)
- Profiles: shared `application.yml` with `spring.profiles.default: local`; `application-local.yml` (health details, DEBUG), `application-prod.yml` (graceful shutdown, no health details, INFO); `application-test.yml` in test resources
- Structured logging: text locally with `[requestId]` in the level pattern; **ECS JSON** in prod via `logging.structured.format.console`
- `RequestIdFilter`: `X-Request-Id` in/out, MDC, safe-ID validation, DEBUG access log (skips `/actuator`, never logs query strings)
- `GlobalExceptionHandler` (extends `ResponseEntityExceptionHandler`): RFC 7807 `ProblemDetail` + `code` + `requestId`; 404 / 409 / 400 with field errors / 500 without leaking internals; Spring MVC's own errors enriched too
- Domain exceptions: `SeatLockException` (base, with `code`), `NotFoundException`, `ConflictException`
- First endpoint: `GET /api/v1/system/time` → `{serverTime, epochMillis}` in UTC, `Cache-Control: no-store`, backed by an injected UTC `Clock` bean
- Tests: **12 passing**: `RequestIdFilterTest` (4 unit), `GlobalExceptionHandlerTest` (6, `@WebMvcTest` via test-only `ErrorTestController`), `SystemControllerTest` (1, fixed clock), `SeatLockApplicationTests` (1, full context)
**Files created/changed:**
- `backend\src\main\java\com\seatlock\SeatLockApplication.java` (moved from `seatlock_backend` package + renamed)
- `com\seatlock\{common,identity,catalog,booking,payment,realtime,assistant}\package-info.java`
- `com\seatlock\common\web\RequestIdFilter.java`; `common\config\ClockConfig.java`; `common\system\SystemController.java`, `ServerTimeResponse.java`
- `common\exception\SeatLockException.java`, `NotFoundException.java`, `ConflictException.java`, `GlobalExceptionHandler.java`
- `src\main\resources\application.yml` (renamed from `.yaml`), `application-local.yml`, `application-prod.yml`; `src\test\resources\application-test.yml`
- Tests: `SeatLockApplicationTests.java` (moved + `@ActiveProfiles("test")`), `common\web\RequestIdFilterTest.java`, `common\exception\GlobalExceptionHandlerTest.java`, `common\system\SystemControllerTest.java`, `testsupport\ErrorTestController.java`
- `progress.md` (added to the repo; it was missing from the Day 1 commit)
**Decisions:**
- See Decisions Log #11–#15
**Metrics recorded:**
- None (no resume metrics yet). Test timings: web-slice tests ~1–6 s, full context test ~7.8 s
**Problems & fixes:**
- `X-Request-Id` header missing → Initializr's package name had stayed `com.seatlock.seatlock_backend`, so component scanning never reached `com.seatlock.common.*` → moved/renamed the main class + context test to `com.seatlock`, renamed `application.yaml` → `.yml`, moved `application-test.yml` to test resources and `RequestIdFilterTest` to its matching folder
- `progress.md` wasn't in the Day 1 commit → included in the Day 2 commit
- Confirmed Boot 4's `@WebMvcTest` package: `org.springframework.boot.webmvc.test.autoconfigure`
**Carry-over:**
- Oracle signup attempt with a family Visa/Mastercard credit card (hosting decision by W2·D4); JetBrains student license
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Package-by-feature vs package-by-layer; component-scan root of `@SpringBootApplication`
- Profiles: `spring.profiles.default` vs `active`, profile-specific files, test profile
- Structured logging (ECS), MDC request correlation, log-injection prevention
- RFC 7807 `ProblemDetail`, `ResponseEntityExceptionHandler`, exception-handler resolution order, not leaking internals on 500
- Bean Validation on records; injecting `Clock` for testable time; `Cache-Control: no-store`
- `@WebMvcTest` slices, `@TestConfiguration` + `@Primary`, MockMvc + JsonPath assertions
**Commit:** `feat(common): add profiles, request IDs, ProblemDetail and time API`

### Week 1 · Day 3: Data model (Thu, Nov 12, 2026)
**Status:** ✅ Done
**Built:**
- ERD (Mermaid) + schema correctness rules in `docs\architecture.md`
- Flyway `V1__init.sql`: 8 tables (`users`, `venues`, `seats`, `events`, `shows`, `show_seats`, `bookings`, `booking_seats`), named `UNIQUE` / `FK` / `CHECK` constraints, FK indexes, and the **partial unique index oversell guard**
- Manual SQL proof (rolled back): a second live claim on the same seat fails with `ux_booking_seats_live_seat`; after the first claim is deactivated the seat can be claimed again; negative price rejected
- `BaseEntity` (IDENTITY id, proxy-safe `equals`/`hashCode`) and `AuditableEntity` (`created_at`/`updated_at` via Spring Data auditing using the app `Clock`, configured in `JpaAuditingConfig`)
- 8 entities + 7 enums; `@Version` on `ShowSeat` and `Booking`; `Booking.addSeat()` keeps `total_paise` in sync with `Math.addExact`
- Repositories: `UserRepository`, `VenueRepository`, `SeatRepository`, `EventRepository`, `ShowRepository`, `ShowSeatRepository`, `BookingRepository`
- Hibernate `ddl-auto: validate` passes against the Flyway schema
- Tests: **15 passing** (new `BookingPersistenceTest`: save + reload with audit timestamps from the fixed clock; DB rejects a second live booking for the same seat; e-mail stored lower-case)
**Files created/changed:**
- `docs\architecture.md`
- `backend\src\main\resources\db\migration\V1__init.sql`
- `common\persistence\BaseEntity.java`, `AuditableEntity.java`; `common\config\JpaAuditingConfig.java`
- `identity\domain\User.java`, `Role.java`, `AuthProvider.java`, `UserRepository.java`
- `catalog\domain\Venue.java`, `Seat.java`, `Event.java`, `Show.java`, `ShowSeat.java`, `EventCategory.java`, `EventStatus.java`, `ShowStatus.java`, `ShowSeatStatus.java` + 5 repositories
- `booking\domain\Booking.java`, `BookingSeat.java`, `BookingStatus.java`, `BookingRepository.java`
- Test: `booking\domain\BookingPersistenceTest.java`
**Decisions:**
- See Decisions Log #16–#20
**Metrics recorded:**
- None (resume metrics start with EXPLAIN baselines on W1·D5). `BookingPersistenceTest` takes ~19 s (context + Flyway); full suite 15 tests
**Problems & fixes:**
- `char(3)` currency column needs `@JdbcTypeCode(SqlTypes.CHAR)` for `validate` to pass; `text` mapped with `columnDefinition = "text"`
- `@EnableJpaAuditing` kept in its own `@Configuration` so `@WebMvcTest` slices don't fail with "JPA metamodel must not be empty"
- Removed `final` from `BaseEntity.equals/hashCode` (Hibernate proxies subclass entities)
- Confirmed Boot 4 test packages: `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`
**Carry-over:**
- Oracle signup attempt with a family Visa/Mastercard credit card (hosting decision by W2·D4); JetBrains student license
**Interview topics covered (Q&A deferred until after v1.0.0):**
- ERD design; partial unique index vs plain unique constraint; composite foreign keys; `CHECK` constraints; naming constraints
- Flyway versioned migrations, checksums, never editing applied migrations; Hibernate `validate` vs `update`
- Optimistic locking with `@Version`; public UUID vs internal bigint IDs; money as integer paise; `timestamptz` + `Instant`
- JPA entity `equals`/`hashCode` with proxies; LAZY associations; cross-module references by ID
- Spring Data auditing with a custom `DateTimeProvider`; `@DataJpaTest` with a real database, transactional rollback
**Commit:** `feat(booking): add V1 schema with oversell guard, entities and repos`

### Week 1 · Day 4: Seed data + Catalog APIs (Fri, Nov 13, 2026)
**Status:** ✅ Done
**Built:**
- `V2__seed.sql`: 3 organizers, 4 venues (Delhi, Mumbai, Bengaluru, Pune) with generated layouts (1,008 seats, one aisle gap per row, PLATINUM/GOLD/SILVER by row), 30 events (1 DRAFT), 90 shows (3 per event, a week apart, 19:30 IST), 22,944 `show_seats` with section prices (2× / 1.5× / 1× a per-category base)
- DTO records: `EventSearchCriteria` (validated query params, default limit 20, UTC time filters), `EventSummaryResponse`, `EventDetailResponse`, `ShowSummaryResponse`, `ShowSeatMapResponse` (+ `VenueRef`, `SectionPrice`, `SeatView`), generic `CursorPage<T>`
- `GET /api/v1/events`: filters (city, category, language, time range), keyset pagination, `LATERAL` next-show + "from" price; dynamic SQL with bound parameters only
- `GET /api/v1/events/{id}`: published event + upcoming shows (404 for drafts/unknown)
- `GET /api/v1/shows/{id}/seats`: grid size, section price legend (most expensive first), every seat with position, aisle flag, price, status
- `InvalidRequestException` → 400 in `GlobalExceptionHandler`
- Tests: **30 passing** (new: `EventCursorTest` 6, `EventSearchCriteriaTest` 4, `CatalogApiIntegrationTest` 5)
**Files created/changed:**
- `db\migration\V2__seed.sql`
- `catalog\api\EventController.java`, `ShowController.java`, `EventSearchCriteria.java`, `EventSummaryResponse.java`, `EventDetailResponse.java`, `ShowSummaryResponse.java`, `ShowSeatMapResponse.java`
- `catalog\application\EventCatalogService.java`, `ShowCatalogService.java`, `EventSearchRepository.java`, `ShowQueryRepository.java`, `EventCursor.java`
- `catalog\domain\EventRepository.java` (`findByIdAndStatus`)
- `common\web\CursorPage.java`; `common\exception\InvalidRequestException.java`, `GlobalExceptionHandler.java` (400 mapping)
- Tests: `catalog\application\EventCursorTest.java`, `catalog\api\EventSearchCriteriaTest.java`, `catalog\api\CatalogApiIntegrationTest.java`
**Decisions:**
- See Decisions Log #21–#25
**Metrics recorded:**
- Seed size: 1,008 seats, 90 shows, 22,944 show_seats. Query baselines (`EXPLAIN ANALYZE`) on W1·D5
**Problems & fixes:**
- Postgres JDBC driver can't bind `java.time.Instant` → bind `OffsetDateTime` at UTC; read with `getObject(..., OffsetDateTime.class).toInstant()`
- Hard-coded counts in API tests would break as seed shows move into the past → assertions computed from the DB
- Confirmed Boot 4 package: `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
**Carry-over:**
- Oracle signup attempt with a family Visa/Mastercard credit card (hosting decision by W2·D4); JetBrains student license
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Set-based seeding with `generate_series`; data-modifying CTEs with `RETURNING`; transactional Flyway migrations on Postgres
- Keyset vs OFFSET pagination; tie-breaker columns; opaque cursors
- `LATERAL` joins; avoiding N+1 with read-model SQL; `JdbcClient` + `RowMapper`; safe dynamic SQL (fixed fragments + bound params)
- DTOs vs entities at the API boundary; record constructor binding of query params; cross-field validation with `@AssertTrue`
- 404 vs 403 for hidden resources; property-based assertions in integration tests
**Commit:** `feat(catalog): add seed data and event list, detail and seat map APIs`

### Week 1 · Day 5: API docs, Testcontainers, search & indexes (Sat, Nov 14, 2026)
**Status:** ✅ Done
**Built:**
- springdoc 3.2.0: Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs`, `OpenApiConfig` metadata, `Catalog` / `System` tags, `@ParameterObject` so search criteria appear as query params
- Testcontainers 2.x (`testcontainers-postgresql`, `testcontainers-junit-jupiter`) + `spring-boot-testcontainers`: `TestcontainersConfiguration` (pgvector PG16 + Redis 7 via `@ServiceConnection`), `@IntegrationTest` meta-annotation; all DB tests moved off the local stack (verified passing with Compose stopped)
- Baseline query plans for the 3 catalog reads (`docs/explain/catalog-queries.sql` → `01-baseline.txt`) and new `docs/CORRECTNESS.md` (oversell-guard table + query-plan analysis)
- `V3__event_search.sql`: generated weighted `tsvector` + GIN index; `q` parameter on `GET /api/v1/events` via `websearch_to_tsquery`
- `V4__catalog_read_indexes.sql`: `ix_show_seats_show_price`, `ix_shows_event_starts` (drops `ix_shows_event`); after-plans in `02-after-indexes.txt` (Q1 ~5× faster, buffers −52%; `min(price)` now an index-only scan, 1 row per show)
- JaCoCo 0.8.15: report in `target/site/jacoco` on `mvnw verify`
- Tests: **33 passing** (new: `OpenApiDocsTest`, full-text search test, `q` validation test)
**Files created/changed:**
- `backend\pom.xml` (springdoc, Testcontainers, JaCoCo); `application.yml` (springdoc block)
- `common\config\OpenApiConfig.java`; `@Tag` on `EventController`, `ShowController`, `SystemController`; `@ParameterObject` on event search
- `db\migration\V3__event_search.sql`, `V4__catalog_read_indexes.sql`
- `catalog\api\EventSearchCriteria.java` (`q`), `catalog\application\EventSearchRepository.java` (FTS filter)
- Tests: `testsupport\TestcontainersConfiguration.java`, `testsupport\IntegrationTest.java`, `common\config\OpenApiDocsTest.java`; updated `SeatLockApplicationTests`, `CatalogApiIntegrationTest`, `BookingPersistenceTest`, `EventSearchCriteriaTest`; `src\test\resources\application-test.yml` (placeholder datasource)
- `docs\CORRECTNESS.md`, `docs\explain\catalog-queries.sql`, `01-baseline.txt`, `02-after-indexes.txt`
**Decisions:**
- See Decisions Log #26–#30
**Metrics recorded:**
- Q1 event list 3.31 → 0.66 ms, buffers 251 → 121; Q2 1.11 → 0.15 ms, buffers 24 → 10 (see Metrics table)
- Coverage: 79% instructions, ~80% lines, 60% branches
**Problems & fixes:**
- springdoc 3.0.x vs Boot 4.1 compatibility risk → moved to 3.2.0 (Council review, user-approved)
- `${POSTGRES_PASSWORD}` has no default, which would break tests without `.env` (CI) → dummy datasource values in `application-test.yml` (Testcontainers supplies the real connection)
- `shows (event_id, starts_at)` index not yet chosen by the planner (90 rows = 2 pages; seq scan is cheaper); expected to pay off at volume (W6·D5)
- Confirmed Testcontainers 2 package: `org.testcontainers.postgresql.PostgreSQLContainer`
**Carry-over:**
- Oracle signup attempt with a family Visa/Mastercard credit card (hosting decision by W2·D4); JetBrains student license
- Small coverage gaps to close by W6·D1: `BaseEntity.equals/hashCode`, `CursorPage.hasMore`, `RequestIdFilter` DEBUG branch
**Interview topics covered (Q&A deferred until after v1.0.0):**
- OpenAPI / springdoc, `@ParameterObject`; API docs generated from code
- Testcontainers, `@ServiceConnection`, Spring test-context caching, meta-annotations
- Reading `EXPLAIN (ANALYZE, BUFFERS)`: seq vs index vs index-only scans, loops, buffers; min/max optimisation; visibility map and `Heap Fetches`
- Index design from measurements; composite index column order; superseding prefix indexes; HOT updates and why not to index hot columns; `CREATE INDEX CONCURRENTLY`
- Postgres full-text search: `tsvector`, weights, stemming, stop words, GIN, `websearch_to_tsquery`; generated columns
- Code coverage: instruction vs line vs branch coverage; why not to gate too early
**Commit:** `feat(catalog): add search, Swagger, Testcontainers and query indexes`

### Week 1 · Day 6: Frontend scaffold + Week 1 review (planned Mon, Nov 16; done Sat, Nov 14, 2026)
**Status:** ✅ Done
**Built:**
- Installed Node 26 LTS, VS Code + extensions (ESLint, Prettier, Tailwind, Docker, GitLens, REST Client)
- `frontend\`: Vite React-TS + Tailwind 4 + React Router 8 + TanStack Query 5 (+ devtools); `Layout` with header, routes `/` → `/events`, `/events`, `/events/:eventId`, 404 page
- Backend CORS: `CorsProperties` (`seatlock.cors.allowed-origins`, validated) + `CorsConfig` for `/api/**` (exact origins, exposed `X-Request-Id`, preflight cached 1 h); `CorsIntegrationTest` (preflight allowed, header exposed, unknown origin 403)
- Typed API layer: `types/api.ts` (DTO mirrors), `api/client.ts` (`ApiError`, `apiGet`), `api/catalog.ts` (`catalogKeys`, `searchEvents`, `getEvent`, `getSeatMap`), `lib/format.ts` (₹ from paise, UTC → IST), smarter retries
- Event list page: URL-driven search/city/category filters, `useInfiniteQuery` "Load more", skeletons, error box with `requestId` + retry, empty state
- Event detail page: description, upcoming shows in IST with "from" price, "Select seats" placeholder (enabled on W3·D1), not-found handling (no API call for invalid IDs), back link keeps list filters, React 19 `<title>`
- Week 1 review (below)
- Tests: backend **36 passing**; frontend `tsc` + ESLint clean, `npm run build` passes
**Files created/changed:**
- Backend: `common\config\CorsProperties.java`, `CorsConfig.java`; `application-local.yml`, `application-prod.yml`, `application-test.yml` (CORS); `.env.example` (`CORS_ALLOWED_ORIGINS`); test `common\config\CorsIntegrationTest.java`
- Frontend: `vite.config.ts`, `index.html`, `.env.example`, `src\main.tsx`, `src\router.tsx`, `src\index.css`, `src\env.d.ts`, `src\types\api.ts`, `src\api\client.ts`, `src\api\catalog.ts`, `src\lib\format.ts`, `src\lib\queryClient.ts`, `src\components\Layout.tsx`, `src\components\ErrorMessage.tsx`, `src\features\events\{filters.ts, navigation.ts, EventFilters.tsx, EventCard.tsx, EventCardSkeleton.tsx, EventDetailSkeleton.tsx, ShowList.tsx}`, `src\pages\{EventListPage.tsx, EventDetailPage.tsx, NotFoundPage.tsx}`
**Decisions:**
- See Decisions Log #31–#35
**Metrics recorded:**
- None new
**Problems & fixes:**
- react-router installed as **v8** (plan text assumed v7); `RouterProvider` from `react-router/dom` still works
- Template `tsconfig` has `erasableSyntaxOnly` → no TS `enum`s or constructor parameter properties (string unions + explicit fields)
- Running `npm install` from Claude's Linux environment would put Linux binaries into `node_modules` → npm commands stay on Windows
**Carry-over:**
- Oracle signup attempt with a family Visa/Mastercard credit card (hosting decision by W2·D4); JetBrains student license
- Coverage gaps (by W6·D1): `BaseEntity.equals/hashCode`, `CursorPage.hasMore`, `RequestIdFilter` DEBUG branch
- Optional cleanup: delete unused template images in `frontend\src\assets`
**Week 1 review:**
- All 6 days delivered ~1.5 days early: foundation, schema with DB-level oversell guard, seeded catalog, catalog APIs with FTS + keyset pagination, Swagger, Testcontainers, measured indexes, React list/detail UI
- Risks for Week 2: (1) hosting decision by W2·D4; (2) heaviest week (JWT, roles, OAuth, caching, first deploy, CI), with Spring Security 7 API changes; (3) refresh-token storage decision (cookie + CSRF/CORS credentials vs header) on W2·D2
- Noted improvements (unscheduled): `/api/v1/cities` endpoint, relevance-ranked search (`ts_rank` + own cursor), Playwright smoke test (stretch)
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Same-origin policy, CORS preflight, exposed headers, why never `*`, credentials + exact origins
- SPA architecture: server state vs UI state, TanStack Query caching/keys/staleTime/retries, infinite queries with cursors, `keepPreviousData`
- URL as state (`useSearchParams`), uncontrolled inputs keyed by URL, router `state` for back-navigation
- Typed API clients, error normalisation, UTC → local time at the UI edge, `Intl` formatting
- Accessibility basics: `role="search"`, labels, `role="alert"`, focus rings
**Commit:** `feat(frontend): scaffold React app with event list and detail pages`

### Week 2 · Day 1: Spring Security, register/login, BCrypt, RS256 JWT (planned Tue, Nov 17; done Sat, Nov 14, 2026)
**Status:** ✅ Done
**Built:**
- Stateless `SecurityFilterChain`: CSRF/sessions/basic/form off, explicit public routes, deny by default; 401/403 rendered as ProblemDetail through MVC's exception resolver
- CORS as a `CorsConfigurationSource` applied by Spring Security before authentication
- RS256 keys: `JwtProperties`, `RsaKeys` (PEM/base64 parsing, pair + size checks), `JwtConfig` (`RSAKey` with thumbprint `kid`, `NimbusJwtEncoder`, `NimbusJwtDecoder` with timestamp/issuer/audience validators on the app `Clock`); local pair generated into `.env`
- `POST /api/v1/auth/register` (201; `EMAIL_TAKEN` 409 incl. race via unique index; `PASSWORD_TOO_LONG`); BCrypt-only `DelegatingPasswordEncoder`
- `POST /api/v1/auth/login`: `DaoAuthenticationProvider` + `AccountUserDetailsService`, `TokenService` (15-min token, `jti`, `roles`), `Cache-Control: no-store`, `INVALID_CREDENTIALS`
- Resource server: bearer validation, `roles` → `ROLE_*`, `INVALID_TOKEN` with `WWW-Authenticate: Bearer error="invalid_token"`; `GET /api/v1/me`; Swagger `bearerAuth` (Authorize button)
- ADR-002; architecture section 2a (login sequence diagram)
- Tests: **82** (new: security rules 5, slice security cases 3, `RsaKeysTest` 5, `JwtConfigTest` 5, `PasswordConfigTest` 2, `RegisterRequestTest` 1, `RegisterIntegrationTest` 5, `TokenServiceTest` 3, `LoginIntegrationTest` 5, `MeIntegrationTest` 7, `UserServiceTest` 3, `JwtAuthenticationConverterTest` 1, `AuthFlowIntegrationTest` 1; `OpenApiDocsTest` extended)
**Files created/changed:**
- `backend\pom.xml` (security, security-oauth2-resource-server, security-test); `application.yml` (`seatlock.jwt`), `application-prod.yml` (required keys), `application-test.yml` (ephemeral keys); `.env.example` (JWT keys + generation steps)
- `identity\security\{SecurityConfig, ProblemDetailSecurityHandler, JwtProperties, RsaKeys, JwtConfig, PasswordConfig, AuthenticatedUser, AccountUserDetailsService, AuthenticationConfig, AccessToken, TokenService}.java`
- `identity\application\{UserService, AuthService}.java`; `identity\api\{AuthController, MeController, RegisterRequest, LoginRequest, UserResponse, TokenResponse}.java`; `identity\domain\User.java` (`normalizeEmail`)
- `common\config\CorsConfig.java` (rewritten), `OpenApiConfig.java` (bearer scheme); `common\exception\GlobalExceptionHandler.java` (security handlers)
- Tests: `identity\security\*Test`, `identity\api\*IntegrationTest`, `RegisterRequestTest`, `identity\application\UserServiceTest`, `testsupport\SecurityRulesSlice.java`, `ErrorTestController` (+2 routes); updated `SystemControllerTest`, `GlobalExceptionHandlerTest`, `OpenApiDocsTest`
- `docs\decisions\ADR-002-jwt-authentication.md`, `docs\architecture.md`, `progress.md`
**Decisions:**
- See Decisions Log #36–#42
**Metrics recorded:**
- None new (W2·D1 JaCoCo numbers still to record)
**Problems & fixes:**
- Boot 4 starter names: `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-security-test`
- Catch-all `@ExceptionHandler(Exception)` would turn `AccessDeniedException` / `AuthenticationException` into 500 → explicit handlers
- `@WebMvcTest` doesn't pick up `@Configuration` security classes → `@SecurityRulesSlice` (import + `@MockitoBean(types = JwtDecoder.class)`)
- `Jwt.getIssuer()` expects a URL; plain `seatlock` issuer read with `getClaimAsString("iss")`
- Seed organizers' `'!'` hash would make `DelegatingPasswordEncoder` throw → never-matching default encoder
**Carry-over:**
- Record JaCoCo numbers; check IntelliJ vulnerable-dependency scan for the new security dependencies (ADR-001)
- Oracle signup attempt with a family Visa/Mastercard credit card (hosting decision by W2·D4); JetBrains student license
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Security filter chain, stateless APIs, when CSRF matters (cookies vs Authorization header), CORS before authentication
- 401 vs 403, `WWW-Authenticate` / RFC 6750 error codes, not leaking failure reasons, account enumeration and timing attacks
- JWT structure, RS256 vs HS256, `kid` / JWK thumbprints, `alg:none` and algorithm confusion, `iss`/`aud`/`exp`/`nbf`/`jti`, clock skew, short TTLs vs revocation
- OAuth2 resource server, `JwtAuthenticationConverter`, authorities vs roles (`ROLE_` prefix)
- BCrypt cost and salt, `DelegatingPasswordEncoder` `{id}` prefixes, the 72-byte limit, NIST 800-63B password rules
- `AuthenticationManager` / `DaoAuthenticationProvider` / `UserDetailsService`; sign-up races (DB constraint as the real guard)
- Slice tests with security, `@MockitoBean`, testing tampered / expired / forged tokens
**Commit:** `feat(identity): add JWT auth with register, login and resource server`

### Week 2 · Day 2: Roles, `@PreAuthorize`, demo users, security matrix (planned Wed, Nov 18; done Sat, Nov 14, 2026)
**Status:** ✅ Done (refresh tokens removed from scope by user decision, #43)
**Built:**
- Access token TTL 8 h, no refresh tokens; ADR-002 and comments updated
- `MethodSecurityConfig`: `@EnableMethodSecurity` + static `RoleHierarchy` (ADMIN ⊃ ORGANIZER ⊃ USER)
- URL role gates for `/api/v1/admin/**` and `/api/v1/organizer/**`
- `GET /api/v1/admin/users` (ADMIN, offset `PageResponse`, size 1–100, no hashes)
- `GET /api/v1/organizer/events` (ORGANIZER; own events incl. drafts, `showCount`, `upcomingShowCount`, `nextShowAt`; one `JdbcClient` query with `FILTER` aggregates); `@CurrentUserId`
- Flyway `V5__demo_users.sql`: demo USER + unlocked demo ORGANIZER (`seatlock-demo`); `AdminBootstrap` from env vars; local admin credentials generated into `.env`
- README "Demo accounts"; Swagger "Try it" instructions; ADR-002 sections 7–8 + role-staleness consequence; architecture section 2a protected-routes table
- Tests: **123** (new: `MethodSecurityTest` 12, `RoleEndpointsIntegrationTest` 4, `AdminBootstrapTest` 6, `DemoAccountsIntegrationTest` 3, `SecurityMatrixIntegrationTest` 16; `OpenApiDocsTest` extended; `LoginIntegrationTest` / `MeIntegrationTest` updated for 8 h)
**Files created/changed:**
- `identity\security\{MethodSecurityConfig, CurrentUserId}.java` (new), `SecurityConfig.java` (role gates)
- `identity\api\{AdminUserController, AdminUserResponse}.java`; `identity\application\{AdminBootstrap, AdminBootstrapProperties}.java`, `UserService.java` (`listUsers`)
- `catalog\api\{OrganizerEventController, OrganizerEventResponse}.java`; `catalog\application\{OrganizerCatalogService, OrganizerEventQueryRepository}.java`
- `common\web\PageResponse.java`; `common\config\OpenApiConfig.java`, `CorsConfig.java` (comment); `GlobalExceptionHandler.java` (comment); `identity\api\TokenResponse.java` (comment)
- `db\migration\V5__demo_users.sql`; `application.yml` (`8h`, `seatlock.admin`), `application-test.yml` (admin forced empty)
- Tests: `identity\security\{MethodSecurityTest, SecurityMatrixIntegrationTest}`, `identity\application\AdminBootstrapTest`, `identity\api\DemoAccountsIntegrationTest`, `catalog\api\RoleEndpointsIntegrationTest`, `testsupport\RoleTestController`; updated `SecurityRulesSlice`, `OpenApiDocsTest`, `LoginIntegrationTest`, `MeIntegrationTest`
- `README.md`, `.env.example`, `docs\decisions\ADR-002-jwt-authentication.md`, `docs\architecture.md`, `progress.md`
**Decisions:**
- See Decisions Log #43–#48
**Metrics recorded:**
- None new (JaCoCo numbers still to record)
**Problems & fixes:**
- `MeIntegrationTest.expiredTokenIsRejected` passed a 1-hour-old token after the TTL moved to 8 h → test now goes back one full configured TTL + 1 min (never hard-code the TTL in tests)
- Stray empty `x` folder created in repo root (Claude's shell can't delete) → removed by user
**Carry-over:**
- Record JaCoCo numbers; IntelliJ vulnerable-dependency scan (ADR-001)
- Create a Google OAuth client (needed for W2·D3)
- Oracle signup attempt with a family Visa/Mastercard credit card (hosting decision by W2·D4); JetBrains student license
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Access vs refresh tokens; token lifetime trade-offs without refresh; why a stateless JWT can't be revoked early
- URL authorization vs method security; `@PreAuthorize`, `RoleHierarchy`, defence in depth; deny by default
- Ownership checks / IDOR prevention (identity from the token, not request params)
- Seeding demo accounts vs bootstrapping admins from env; privilege-escalation via account pre-registration; idempotent startup tasks with multiple instances
- Offset vs keyset pagination; why not to serialize Spring's `Page`
- Table-driven (parameterized) security tests; `jwt()` / `user()` test post-processors vs real signed tokens
**Commit:** `feat(identity): add roles, method security, demo users and admin bootstrap`

### Week 2 · Day 3: Google sign-in + frontend auth (planned Thu, Nov 19; done Sat, Nov 14, 2026)
**Status:** ✅ Done
**Built:**
- Google OAuth client created (Testing mode); `spring-boot-starter-security-oauth2-client`; registration `google` from `.env`; dummy client in tests
- `OAuth2LoginConfig` (`@Order(1)` chain): PKCE, `RedisOAuth2AuthorizationRequestRepository` (Redis + binding cookie), `DiscardingAuthorizedClientRepository`; API chain now `@Order(2)`
- `GoogleAccountService` (B1 linking), `OneTimeCodeService` (A2), `GoogleLoginHandlers` (success → `/auth/callback?code=`, failure → `/login?error=`), `FrontendProperties`
- `POST /api/v1/auth/oauth/exchange` → `TokenResponse`; `UnauthorizedException` → 401 with code (`INVALID_OAUTH_CODE`)
- Frontend: `api/client.ts` (Bearer, `INVALID_TOKEN` handling, `apiPost`), `api/auth.ts`, `api/organizer.ts`, `lib/tokenStorage.ts`, `features/auth/*` (`AuthProvider`, `useAuth`, `RequireAuth`, roles, safe redirects, Google button, demo buttons), pages `LoginPage`, `RegisterPage`, `AuthCallbackPage`, `AccountPage`, `OrganizerEventsPage`; header `UserMenu`
- ADR-003 (with sequence diagram); architecture section 2a; README demo + local Google setup
- Tests: backend **144** (new: `GoogleSignInStartIntegrationTest` 2, `RedisOAuth2AuthorizationRequestRepositoryTest` 2, `GoogleAccountServiceTest` 4, `OneTimeCodeServiceTest` 3, `GoogleLoginHandlersTest` 4, `GoogleCallbackIntegrationTest` 2, `OAuthCodeExchangeIntegrationTest` 4); frontend `tsc` + ESLint clean
**Files created/changed:**
- Backend: `pom.xml`; `application.yml` (Google registration), `application-local.yml` / `-prod.yml` / `-test.yml` (`seatlock.frontend.base-url`, test client); `.env.example` (`GOOGLE_*`, `FRONTEND_BASE_URL`)
- `identity\oauth\{OAuth2LoginConfig, RedisOAuth2AuthorizationRequestRepository, DiscardingAuthorizedClientRepository, OneTimeCodeService, GoogleLoginHandlers}.java`; `identity\application\{GoogleAccountService, AuthService}.java`; `identity\api\{AuthController, OAuthCodeExchangeRequest}.java`; `identity\security\{SecurityConfig, AuthenticatedUser}.java`; `common\config\FrontendProperties.java`; `common\exception\{UnauthorizedException, GlobalExceptionHandler}.java`
- Backend tests: `identity\oauth\*Test`, `identity\application\GoogleAccountServiceTest`, `identity\api\OAuthCodeExchangeIntegrationTest`
- Frontend: `src\types\api.ts`, `src\api\{client, auth, organizer}.ts`, `src\lib\tokenStorage.ts`, `src\features\auth\*`, `src\components\{Layout, UserMenu}.tsx`, `src\pages\{LoginPage, RegisterPage, AuthCallbackPage, AccountPage, OrganizerEventsPage}.tsx`, `src\router.tsx`, `src\main.tsx`
- `docs\decisions\ADR-003-google-sign-in.md`, `ADR-002` (cross-ref), `docs\architecture.md`, `README.md`, `progress.md`
**Decisions:**
- See Decisions Log #49–#53
**Metrics recorded:**
- None new (JaCoCo numbers still to record)
**Problems & fixes:**
- Default OAuth2 client keeps the authorization request in the HTTP session → replaced with Redis + binding cookie (stateless, multi-instance)
- `OAuth2AuthorizationRequest.Builder.additionalParameters(Map<String,Object>)` won't take a `Map<String,String>` → used the `Consumer` overload
- React StrictMode double-mount would POST the single-use code twice → exchange runs as a TanStack query keyed by the code (de-duplicated); post-login redirect read with a side-effect-free `peek`
- `eslint-plugin-react-refresh`: a file exporting a component must export only components → context, hook and helpers split into separate files
**Carry-over:**
- Record JaCoCo numbers; IntelliJ vulnerable-dependency scan (ADR-001) incl. OAuth2 client deps
- Add the prod redirect URI to the Google client (W2·D5)
- Oracle signup attempt with a family Visa/Mastercard credit card (hosting decision by W2·D4); JetBrains student license
**Interview topics covered (Q&A deferred until after v1.0.0):**
- OAuth 2.0 authorization-code flow vs OIDC (ID token, `nonce`), `state`, PKCE (S256) for confidential clients, redirect URI exact matching
- Stateless OAuth login across instances (no session, external state store); login CSRF and browser-binding cookies; SameSite Lax vs Strict vs None
- Token handoff to an SPA on another site: fragment vs one-time code exchange; single-use codes, hashing secrets at rest, `Referrer-Policy`
- Account linking and takeover via unverified e-mail; open redirects
- Multiple `SecurityFilterChain`s with `securityMatcher` + `@Order`
- SPA auth: token storage trade-offs (localStorage vs cookie, XSS), auth context, protected routes (UX vs server enforcement), cross-tab sync, cache scoping per user, StrictMode double effects
**Commit:** `feat(identity): add Google sign-in with one-time code and frontend auth`

### Week 2 · Day 4: Redis caching (measured), create show, venue overlap guard (planned Fri, Nov 20; done Sun, Nov 15, 2026)
**Status:** ✅ Done (seat-map caching built, measured, and removed)
**Built:**
- Cache infrastructure: `CacheConfig` (own `RedisCacheManager`, typed JSON serializer per cache, SCAN batch clear, statistics, `LoggingCacheErrorHandler`, kill switch), `CacheNames`, `TypedJsonRedisSerializer`; Redis timeout 500 ms; `/actuator/metrics|caches` ADMIN-only (local)
- `CatalogReadCache`: `@Cacheable` event detail (5 min) and list pages (60 s, normalized + URL-encoded keys); started shows dropped per request; cache advice ordered before transactions
- Flyway `V6`: `btree_gist`, `shows.ends_at` (backfilled), `ck_shows_ends_after_start`, exclusion constraint `ex_shows_venue_no_overlap`
- `POST /api/v1/organizer/events/{eventId}/shows` (`CreateShowRequest` / `CreatedShowResponse`, `ShowCreationRepository` set-based seat insert, `SHOW_OVERLAP` / `SECTION_PRICES_MISMATCH` / `SHOW_TOO_SOON` / `UNKNOWN_VENUE` / `EVENT_CANCELLED`)
- `ShowScheduledEvent` + `CatalogCacheEvictor` (AFTER_COMMIT eviction)
- Benchmarks: `load-tests/bench-catalog.ps1`, `load-tests/cache-stats.ps1`, `docs/benchmarks.md` (Run 1 superseded, Run 2 valid); seat-map split + `venueLayout`/`showPrices` caches **reverted** after measurement
- ADR-004; CORRECTNESS.md section 1b (venue guard); architecture section 2b
- Tests: **173** (new: `CacheConfigIntegrationTest` 3, `TypedJsonRedisSerializerTest` 3, `CacheKillSwitchTest` 2, `CatalogCachingIntegrationTest` 6, `EventSearchKeyTest` 4, `CreateShowIntegrationTest` 5, `CacheEvictionIntegrationTest` 2, `SecurityMatrixIntegrationTest` +4 create-show rows)
**Files created/changed:**
- `backend\pom.xml` (`spring-boot-starter-cache`); `application.yml` (`seatlock.cache.enabled`, Redis timeout), `application-local.yml` (actuator metrics/caches)
- `common\cache\{CacheConfig, CacheNames, TypedJsonRedisSerializer}.java`; `identity\security\SecurityConfig.java` (`/actuator/**` ADMIN)
- `catalog\application\{CatalogReadCache, CatalogCacheEvictor, ShowCreationRepository, EventCatalogService, OrganizerCatalogService}.java`; `catalog\events\ShowScheduledEvent.java`; `catalog\api\{CreateShowRequest, CreatedShowResponse, OrganizerEventController}.java`; `catalog\domain\Show.java` (`endsAt`, `TURNOVER`)
- `db\migration\V6__show_end_time_and_no_overlap.sql`
- Tests: `common\cache\*`, `catalog\application\{CatalogCachingIntegrationTest, CacheEvictionIntegrationTest, EventSearchKeyTest}`, `catalog\api\CreateShowIntegrationTest`, `SecurityMatrixIntegrationTest`
- `load-tests\{bench-catalog.ps1, cache-stats.ps1}`; `docs\{benchmarks.md, CORRECTNESS.md, architecture.md}`, `docs\decisions\ADR-004-catalog-caching.md`, `progress.md`
**Decisions:**
- See Decisions Log #54–#61
**Metrics recorded:**
- Event detail p95 −32% (5.33 → 3.61 ms), event list p95 −21% (6.46 → 5.12 ms) vs original; seat-map cache rejected (+59% p95). See Metrics table
**Problems & fixes:**
- Run 1 benchmark invalid: the seat map's "cache off" was the new split path, not the original; DEBUG logging on; single runs → added the V0 baseline (git worktree on `e7dd410`), INFO logging, 3-run medians
- Cache hits still opened a DB transaction (class-level `@Transactional` around the cache call) → cache advice ordered first, read services non-transactional; HikariCP-based test
- Weak metrics test (meter existence only) → asserts +1 miss / +2 hits through the real HTTP path; a direct `Cache.get()` miss counted twice in a test (unexplained, library source not reachable) while the `@Cacheable` path counts exactly
- `CacheStatistics.getRetrievals()` doesn't exist in Spring Data Redis 4 → only `getHits()` / `getMisses()`
- Splitting the seat map + caching layout/prices was slower than the single join (JSON decoding > query) → reverted
- JUnit `@ParameterizedTest(name)` uses `MessageFormat`: literal `{id}` is invalid → `:id`
- PowerShell: a `foreach` statement can't be piped → collect into a variable first; script placeholders like `<SEATLOCK_ADMIN_PASSWORD>` are easy to miss → `cache-stats.ps1` reads `.env` itself
- NamedParameterJdbcTemplate expands collections into `?, ?` lists → section prices bound as a generated `VALUES (:s0, :p0), …` list
**Carry-over:**
- Record JaCoCo numbers; IntelliJ vulnerable-dependency scan (ADR-001)
- Add the prod redirect URI to the Google client (W2·D5)
- JetBrains student license
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Cache-aside with Spring Cache; TTLs; what not to cache (hot, per-user or fast-changing data); cache stampede and staleness windows
- Redis serialization security (default typing / Java serialization gadgets); SCAN vs KEYS; cache metrics and why benchmark hit rates mislead
- Proxy-based AOP pitfalls: self-invocation, advice ordering (`@EnableCaching(order)` vs `@Transactional`)
- Evicting after commit (`@TransactionalEventListener`), Observer pattern / domain events
- Exclusion constraints, GiST, `btree_gist`, `tstzrange` and half-open intervals; "check then insert" races; testing races with a start gate
- Set-based inserts; safe dynamic SQL (generated placeholders, bound values)
- Benchmark methodology: baselines, warm-up, percentiles (p50/p95/p99), noise, medians of repeated runs, logging overhead; making decisions from measurements (and reverting)
**Commit:** `feat(catalog): add measured Redis caching, create-show API and venue overlap guard`

### Week 2 · Day 5: Docker image, prod Compose + Nginx/TLS, first live deploy (planned Sat, Nov 21; done Mon, Nov 16, 2026)
**Status:** ✅ Done (deep-link refresh + Google sign-in on the live site re-tested after the push)
**Built:**
- `backend\Dockerfile` (multi-stage JDK→JRE, layered jar, non-root, liveness `HEALTHCHECK`) + `.dockerignore`; verified locally in prod mode: probes UP, `healthy`, uid `spring`, API OK
- Prod config: forwarded headers (`native`), health probes, readiness group with `db` + `redis`; Redis repositories disabled
- `infra\docker-compose.prod.yml` (nginx, app, postgres, redis, certbot), `infra\nginx\templates\seatlock.conf.template` (TLS, HSTS, gzip, login rate limit, actuator hidden, WebSocket-ready, Docker DNS resolver), `infra\.env.prod.example`, `infra\scripts\init-env-prod.sh`
- Azure VM (India South Central, B2als_v2, Ubuntu 24.04), SSH restricted to my IP, DuckDNS, Docker + 2 GB swap, Let's Encrypt certificate, stack up and healthy
- Vercel frontend (`frontend\vercel.json` SPA rewrite), prod CORS + frontend URL, Google prod redirect URI
- Live smoke test passed: 301 → HTTPS, readiness UP, actuator 404, events, demo login, admin login, CORS header, rate limit (400 ×5 → 429)
- `docs\deploy.md` runbook (setup, smoke test, redeploy, config, ops/backup, stop/start, troubleshooting, decommission); ADR-005; README live links + stack line
**Files created/changed:**
- `backend\{Dockerfile, .dockerignore}`, `backend\src\main\resources\{application.yml, application-prod.yml}`
- `infra\{docker-compose.prod.yml, .env.prod.example}`, `infra\nginx\templates\seatlock.conf.template`, `infra\scripts\init-env-prod.sh`
- `frontend\vercel.json`; `.gitignore` (`!.env.prod.example`, `.vercel/`)
- `docs\deploy.md`, `docs\decisions\ADR-005-hosting.md`, `README.md`, `progress.md`
**Decisions:**
- See Decisions Log #62–#70
**Metrics recorded:**
- JaCoCo 92.5% instructions / 92.1% lines / 73.1% branches (173 tests); container startup ~9.0 s; image content 194 MB
**Problems & fixes:**
- B2pls_v2 (ARM) `NotAvailableForSubscription` in Central India → x64 B2als_v2; "size not supported for arm64" = x64 size with Arm64 architecture selected
- Deployment `RequestDisallowedByAzure`: student policy allows only `uaenorth, malaysiawest, indiasouthcentral, indonesiacentral, eastasia` → India South Central
- Frontend "Cannot reach the SeatLock server": CORS blocked origin `https://seatlock-nu.vercel.app` (env still had the guessed URL) → exact origin in `.env.prod` + `--force-recreate app`
- Vercel 404 on refresh: `vercel.json` not yet on GitHub (Vercel builds from Git) → fixed by today's push
- `Permissions-Policy … browsing-topics` console errors come from Vercel's 404 page, not the app
- Verify command used `?size=` → the list parameter is `limit`
**Carry-over:**
- Re-test F5 on `/events/<id>` and Google sign-in on the live site after the push
- JetBrains student license; IntelliJ vulnerable-dependency scan (ADR-001); re-upload `progress.md` to Project knowledge
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Multi-stage Docker builds, layered jars, image layer caching, non-root containers, PID 1 and SIGTERM / graceful shutdown, JVM container memory (`MaxRAMPercentage`)
- Liveness vs readiness probes; container healthchecks; `depends_on: service_healthy`
- Reverse proxy duties: TLS termination, `X-Forwarded-*` and trusted proxies, HSTS, rate limiting (leaky bucket, burst), hiding internal endpoints
- Let's Encrypt HTTP-01 challenge, standalone vs webroot, renewal without downtime
- Secrets management on a single host; why prod keys differ from dev; least-exposed network topology
- CORS (exact origins, preflight), SPA history-API routing and server rewrites, build-time vs runtime env vars in Vite
- Cloud cost control: burstable VMs, deallocate vs shutdown, region policies, budget alerts, decommissioning
**Commit:** `feat(infra): containerize backend and deploy to Azure VM with Nginx, TLS and Vercel`

### Week 2 · Day 6: CI/CD with GitHub Actions (planned Mon, Nov 23; done Mon, Nov 16, 2026)
**Status:** ✅ Done (ruleset/merge settings created by me after the final push)
**Built:**
- `.github\workflows\ci.yml`: backend verify (Testcontainers, JaCoCo summary + artifact), frontend lint + build
- `.github\workflows\deploy.yml`: `image` (GHCR, `sha-<12>` + `latest`, `GIT_SHA` build-arg, gha cache) → `deploy` (SSH forced-command key, pinned host key) → smoke test (readiness + revision); manual `tag` input for rollback; `production` environment
- `infra\scripts\deploy.sh` (validate → lock → pull → switch → health-wait → auto-rollback); Compose `app` now `image: ${APP_IMAGE}` from GHCR (no builds on the VM)
- `GET /api/v1/system/version` (`VersionResponse`, `seatlock.build.revision` ← `SEATLOCK_REVISION` ← Docker `ARG GIT_SHA`) + test (174 tests)
- VM: CI key with `command=…deploy.sh,restrict`, fail2ban, NSG SSH → Any; GitHub secrets; GHCR package public
- Drills: rollback to an earlier tag via Run workflow; bad tag fails at pull without touching the live version; roll forward
- **Bug found by CI:** `CacheConfigIntegrationTest` / `CatalogCachingIntegrationTest` flaked only on GitHub: cache keys vanished right after being written. Cause: `Cache.evict()`/`clear()` may be deferred (Spring Data Redis 4 defers them), so a deferred delete landed after the next write. Fix: `evictIfPresent()`/`invalidate()` in tests **and** in production `CatalogCacheEvictor`
- README CI/Deploy badges; `docs\deploy.md` section 3 CI/CD (pipeline, rollback, version check, security setup table, key rotation) + troubleshooting rows; ADR-005 CI/CD consequence
**Files created/changed:**
- `.github\workflows\{ci.yml, deploy.yml}`; `infra\scripts\deploy.sh`; `infra\{docker-compose.prod.yml, .env.prod.example}`
- `backend\Dockerfile`; `backend\src\main\resources\application.yml`; `common\system\{SystemController, VersionResponse}.java`; `catalog\application\CatalogCacheEvictor.java`
- Tests: `SystemControllerTest`, `CacheConfigIntegrationTest`, `CatalogCachingIntegrationTest`, `CacheEvictionIntegrationTest`
- `README.md`, `docs\deploy.md`, `docs\decisions\ADR-005-hosting.md`, `.gitignore` (`ci-reports/`), `progress.md`; `mvnw`/`*.sh` stored executable in Git
**Decisions:**
- See Decisions Log #71–#76 (and #9 superseded)
**Metrics recorded:**
- 174 tests; CI/deploy timings (see Metrics)
**Problems & fixes:**
- CI-only test failures (TTL `-2`; +1 DB connection during "hits") → deferred cache deletes; immediate `evictIfPresent`/`invalidate` (#76). Diagnosed from the downloaded `surefire-reports` (re-run failed differently = timing, not a fixed order)
- GitHub runners have random IPs, SSH was limited to my IP → option A (open + key-only + fail2ban + forced-command CI key)
- `mvnw` committed from Windows without the executable bit → `chmod +x` in CI + `git update-index --chmod=+x`
- One-commit-per-day required amend + force-push to `main`, which conflicts with branch protection → workflow changed to daily branch + squash-merged PR (#75)
**Carry-over:**
- Create ruleset `protect-main` + squash-only merge settings (before W3·D1)
- JetBrains student license; IntelliJ vulnerable-dependency scan; re-upload `progress.md` to Project knowledge
**Interview topics covered (Q&A deferred until after v1.0.0):**
- CI vs CD; workflow triggers (`push`, `pull_request`, `workflow_run`, `workflow_dispatch`), jobs/steps, caching, artifacts, concurrency, least-privilege `permissions`
- Build once / deploy the same artifact; immutable SHA tags vs `latest`; container registries (GHCR) and token scopes
- Push vs pull deployments; SSH key auth, `authorized_keys` forced commands (`restrict`), host-key pinning (MITM), fail2ban; secrets management in CI
- Health-gated deploys, automatic rollback, smoke tests that verify the running version; deploy locking
- Flaky tests: order/timing dependence, CI vs local differences, reading surefire reports; `Cache.evict` vs `evictIfPresent` semantics
- Trunk protection: PRs, required status checks, squash merge, linear history
**Commit:** `ci: add GitHub Actions CI, GHCR images and SSH deploy with rollback` (squashed into one commit on `main`)

### Week 3 · Day 1: Live seat map (layout + live status) (planned Tue, Nov 24; done Mon, Nov 16, 2026)
**Status:** ✅ Done
**Built:**
- Hold key schema (`HoldKeys`: `hold:show:{showId}:seat:{seatId}` → `holdId`, TTL = hold) and `SeatHoldReader` (one `MGET` per show) behind the catalog port `SeatHoldLookup`
- Seat map overlays HELD from Redis on DB status (BOOKED wins, only AVAILABLE seats looked up); Redis down → DB view + `holdsLive: false`; `Cache-Control: no-store`; seat-map service no longer `@Transactional`
- Redis-down drill found 512 ms per request (commands queued during reconnect) → `RedisClientConfig` with Lettuce `REJECT_COMMANDS` → ~5 ms
- Benchmark B0 (`main` via git worktree) / B1 overlay 0 holds / B2 50 holds / B3 Redis down, two sessions; `load-tests/seed-holds.ps1`; `bench-catalog.ps1 -Only seats -ShowId`
- Frontend `/shows/:showId`: seat grid by `gridCol` (aisle gap), section headings + prices, legend with symbol per state, native buttons with `aria-pressed`/labels, selection ≤ 6 derived from the latest map ("taken by someone else" notice), ₹ total, disabled "Hold seats", Refresh + "Updated" time, Redis-down banner, not-found page; "Select seats" links enabled
- Docs: architecture section 2c (sequence diagram), CORRECTNESS section 1c, ADR-004 update, new `docs/FAILURES.md` (F1), benchmarks section 2
- Tests: **187** (new: `HoldKeysTest` 2, `SeatHoldReaderIntegrationTest` 4, `ShowCatalogServiceTest` 4, `SeatMapLiveStatusIntegrationTest` 2, `RedisClientConfigIntegrationTest` 1); frontend `tsc` + ESLint clean
**Files created/changed:**
- Backend: `booking\hold\{HoldKeys, SeatHoldReader}.java`; `catalog\application\{SeatHoldLookup, ShowCatalogService}.java`; `catalog\api\{ShowSeatMapResponse, ShowController}.java`; `common\config\RedisClientConfig.java`
- Backend tests: `booking\hold\{HoldKeysTest, SeatHoldReaderIntegrationTest}`, `catalog\application\ShowCatalogServiceTest`, `catalog\api\SeatMapLiveStatusIntegrationTest`, `common\config\RedisClientConfigIntegrationTest`
- Frontend: `src\pages\ShowSeatsPage.tsx`, `src\features\seatmap\{SeatMap, SeatLegend, SelectionSummary, SeatMapSkeleton}.tsx`, `src\features\seatmap\seats.ts`, `src\router.tsx`, `src\features\events\ShowList.tsx`, `src\lib\format.ts`, `src\types\api.ts`
- `load-tests\{bench-catalog.ps1, seed-holds.ps1}`; `docs\{architecture.md, CORRECTNESS.md, FAILURES.md, benchmarks.md}`, `docs\decisions\ADR-004-catalog-caching.md`, `progress.md`
**Decisions:**
- See Decisions Log #77–#82
**Metrics recorded:**
- Seat map overlay: no measurable cost vs W2·D6 (p95 6.87 → 6.36 ms, session 1); Redis down 512 → ~5 ms (see Metrics table)
**Problems & fixes:**
- Redis-down fallback took 0.51 s per request → Lettuce queued commands while reconnecting → `REJECT_COMMANDS` (needs an app restart to take effect; the first re-test was on the old process)
- `seed-holds.ps1` used `docker compose ps`, which failed because Compose reads `.env` from `infra\` → fixed container name `seatlock-redis`; `docker ps -qf name=redis` also matched another project's container
- `/api/v1/shows/1/seats` → 404 (show 1 not bookable) and wrong field name in my snippet (`shows` vs `upcomingShows`) → pick show/seat from the API
- Benchmark CSV held two sessions + extra runs → results reported per session from the raw rows
- ESLint `react-refresh/only-export-components`: shared seat styles moved from `SeatMap.tsx` to `seats.ts`
**Carry-over:**
- JetBrains student license; IntelliJ vulnerable-dependency scan
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Redis key design, TTL as source of truth, lazy expiry; `MGET` vs pipelining vs `SCAN`; cluster hash tags for multi-key Lua
- Merging durable (DB) and ephemeral (Redis) state; graceful degradation; why not to hold a DB transaction across remote calls (pool exhaustion)
- Lettuce disconnected behaviour, auto-reconnect, command timeouts vs fail-fast; circuit breakers for hung dependencies
- Dependency inversion between modules (ports); avoiding package cycles
- `Cache-Control: no-store` vs `no-cache`; Spring Security default cache headers
- Benchmarking with baselines in the same session, medians, isolating causes; failure drills with before/after numbers
- React: rendering grids from layout data, CSS grid placement, accessible toggle buttons (`aria-pressed`), non-colour cues, derived state vs effects, TanStack Query `staleTime`/refetch-on-focus
**Commit:** `feat(catalog): live seat map with Redis hold overlay and seat-map UI (W3·D1)` (squashed into one commit on `main`)

### Week 3 · Day 2: Atomic seat holds (planned Wed, Nov 25; done Mon, Nov 16, 2026)
**Status:** ✅ Done
**Built:**
- `hold_seats.lua` (one hold per user per show, all-or-nothing over N seats, one TTL for seat keys + user key + record hash) and `release_seats.lua` (deletes only keys still owned by the `holdId`)
- `RedisHoldStore` (`RedisScript.of(ClassPathResource)`, EVALSHA), `HoldAttempt` (sealed: `Held` / `SeatsTaken` / `HoldExists`), `HoldRecord`; `HoldKeys.userShowKey/recordKey`
- `HoldService` (input checks, `findHoldableSeats` in catalog: bookable + seats via one `IN` query, not started, not BOOKED; UUID `holdId`; `expiresAt` from the `Clock`; server prices with `Math.addExact`; Redis down → 503); `HoldProperties` (8 min, 6 seats)
- API: `HoldController` (`POST /api/v1/shows/{showId}/holds`, `GET`/`DELETE /api/v1/holds/{holdId}`), `CreateHoldRequest`, `HoldResponse`; `SeatLockException.getProperties()` → `seatIds` in 409; `ServiceUnavailableException` → 503 + `Retry-After`
- Race tests (20 threads ×5): one winner, overlapping holds never share a seat, one user → one hold
- Fix: SDR 4 async cache writes → `immediateWrites()` + regression test (FAILURES.md F2)
- Docs: ADR-006, architecture section 2d (sequence diagram), CORRECTNESS section 1d, FAILURES F2
- Tests: **234** (new: `RedisHoldStoreIntegrationTest` 7, `HoldServiceTest` 12, `HoldApiIntegrationTest` 6, `HoldRaceIntegrationTest` 15, security matrix +5, `HoldKeysTest` +1, `CacheConfigIntegrationTest` +1)
**Files created/changed:**
- `backend\src\main\resources\redis\{hold_seats.lua, release_seats.lua}`; `application.yml` (`seatlock.holds`)
- `booking\hold\{RedisHoldStore, HoldAttempt, HoldRecord, HoldService, HoldProperties, Hold, SeatsUnavailableException, HoldKeys}.java`; `booking\api\{HoldController, CreateHoldRequest, HoldResponse}.java`
- `catalog\application\{HoldableShow, ShowCatalogService, ShowQueryRepository}.java`; `common\exception\{SeatLockException, ServiceUnavailableException, GlobalExceptionHandler}.java`; `common\cache\CacheConfig.java`
- Tests: `booking\hold\{RedisHoldStoreIntegrationTest, HoldServiceTest, HoldRaceIntegrationTest, HoldKeysTest}`, `booking\api\HoldApiIntegrationTest`, `identity\security\SecurityMatrixIntegrationTest`, `common\config\OpenApiDocsTest`, `common\cache\CacheConfigIntegrationTest`
- `docs\decisions\ADR-006-seat-holds.md`, `docs\{architecture.md, CORRECTNESS.md, FAILURES.md}`, `progress.md`
**Decisions:**
- See Decisions Log #83–#89
**Metrics recorded:**
- Race smoke: exactly 1 winner in every run (20 threads ×5); 234 tests
**Problems & fixes:**
- `HoldServiceTest` used `"h1"` after the UUID check was added → real UUIDs; the 404 test had been passing for the wrong reason → now verifies the owner/expiry path is reached + a separate malformed-ID test
- Manual test got 409 with no keys and seat AVAILABLE → `SHOW_STARTED` (seed shows start 19:30 IST; show 34 had just started). PS 5 hides error bodies → read `$_.Exception.Response` stream; pick shows from `upcomingShows`
- `seed-holds.ps1 -Clear` removes only seat keys; a real hold also has user + record keys → clear `hold:*` for a clean slate
- `GlobalExceptionHandler` (common) importing a booking exception → generic `getProperties()` instead
- Cache test `-2` TTL → SDR 4 async cache writes (F2); `nonLockingRedisCacheWriter(factory, lambda)` resolves to the `BatchStrategy` overload → `RedisCacheWriter.create(factory, configurer)`; signatures read from the local jar
- Stray `HoldKeysTest.java.tmp` (shell can't delete) → removed by me
**Carry-over:**
- D5 UI: "This show has started" state on the seat map for started shows
- JetBrains student license; IntelliJ vulnerable-dependency scan
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Check-then-act races; Lua atomicity in Redis; all-or-nothing multi-key writes; `SET PX`, shared TTLs; EVAL vs EVALSHA
- Ownership tokens (`holdId` compare-and-delete) vs fencing tokens; why blind `DEL` is unsafe after expiry
- Redis hold + DB unique index (defence in depth) vs optimistic vs pessimistic locking; Redis without persistence
- Validating against durable state before claiming ephemeral state; server-side pricing; 404 vs 403 for others' resources; input → Redis key safety
- HTTP semantics: 201 + `Location`, 204, 409 with machine-readable details, 503 + `Retry-After`
- Concurrency testing: start gates (`CountDownLatch`), real thread pools, repeated runs, collecting every outcome
- Async vs immediate cache writes; diagnosing flaky tests by mechanism, not symptom
**Commit:** `feat(booking): atomic multi-seat holds with Lua, HoldService and hold API (W3·D2)` (squashed into one commit on `main`)

### Week 3 · Day 3: Idempotency keys for `POST /holds` (planned Thu, Nov 26; done Mon, Nov 16, 2026)
**Status:** ✅ Done
**Built:**
- Flyway `V7__idempotency_keys.sql`: per-user unique key, fingerprint, IN_PROGRESS/COMPLETED + stored response (`CHECK` ties them), `locked_at` lease, `expires_at` + index
- `IdempotencyStore` (claim / find / takeOver / complete / release / deleteExpired; single auto-committed statements), `IdempotencyRecord`, `IdempotencyProperties` (24 h, 30 s)
- `@Idempotent` + `IdempotencyInterceptor` (400 missing/malformed key, 422 reuse, replay with `Idempotent-Replayed`, 409 in progress, lease takeover, release on failure, never release after success) + `IdempotencyConfig` (Spring AOP advisor); `UnprocessableRequestException` → 422
- `POST /api/v1/shows/{showId}/holds` requires the key; Swagger header via `OperationCustomizer`; `HoldExistsException` with `holdId`
- `SchedulingConfig` + hourly `IdempotencyCleanupJob`; verified locally (planted expired row → `Deleted 1 expired idempotency keys`)
- Docs: ADR-007, CORRECTNESS section 1e, architecture section 2d update
- Tests: **259** (new: `IdempotencyStoreIntegrationTest` 9, `IdempotencyInterceptorTest` 8, `HoldIdempotencyIntegrationTest` 6, `IdempotencyCleanupJobTest` 2; `HoldApiIntegrationTest` sends keys; `OpenApiDocsTest` checks the header)
**Files created/changed:**
- `db\migration\V7__idempotency_keys.sql`; `application.yml` (`seatlock.idempotency`), `application-test.yml` (`seatlock.scheduling.enabled: false`)
- `common\idempotency\{Idempotent, IdempotencyInterceptor, IdempotencyConfig, IdempotencyStore, IdempotencyRecord, IdempotencyProperties, IdempotencyCleanupJob}.java`; `common\config\{SchedulingConfig, OpenApiConfig}.java`; `common\exception\{UnprocessableRequestException, GlobalExceptionHandler}.java`
- `booking\api\HoldController.java`; `booking\hold\{HoldExistsException, HoldService}.java`
- Tests: `common\idempotency\{IdempotencyStoreIntegrationTest, IdempotencyInterceptorTest, IdempotencyCleanupJobTest}`, `booking\api\{HoldIdempotencyIntegrationTest, HoldApiIntegrationTest}`, `common\config\OpenApiDocsTest`
- `docs\decisions\ADR-007-idempotency-keys.md`, `docs\{CORRECTNESS.md, architecture.md}`, `progress.md`
**Decisions:**
- See Decisions Log #90–#96
**Metrics recorded:**
- 20 concurrent claims → 1 winner; 10 concurrent duplicate POSTs → 1 hold; 259 tests
**Problems & fixes:**
- W3·D2 PR conflicted on every file: `w3-d2` was branched before #1 was squash-merged → `git rebase --onto origin/main cdd7850` + `--force-with-lease` (#96)
- `@Lazy` on the advisor's dependencies would need a CGLIB proxy of the `IdempotencyProperties` record (final) → `ObjectProvider` lookup on first call
- Mockito trap: re-stubbing `when(store.claim(any…))` calls the previous `thenAnswer` with `""` arguments and overwrote the captured hash → copy the value before re-stubbing
- PowerShell multi-line `psql -c "…"` got split → pipe a here-string into `docker exec -i … psql`
- `git fetch` from Claude's shell left `.git` lock files (shell can't delete) → renamed out of the way; delete `.git\*.stale-claude` files when convenient
**Carry-over:**
- D5 UI: "This show has started" state; frontend sends a fresh `Idempotency-Key` per hold attempt
- JetBrains student license; IntelliJ vulnerable-dependency scan
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Idempotency keys (Stripe-style): scope, fingerprints, replay vs reject, 409 in-progress vs 422 reuse, what to store, retention
- Race-safe claims with unique constraints and `INSERT … ON CONFLICT … RETURNING`; why autocommit matters for visibility
- Crash windows between side effect and record; leases + compare-and-set takeover; at-least-once vs exactly-once
- Spring AOP without AspectJ: advisors, pointcuts, infrastructure role, advice ordering vs method security; early-instantiated post-processors
- Scheduled jobs on multiple instances (idempotent work vs distributed locks); disabling schedulers in tests
- OpenAPI customizers; HTTP 422 (RFC 9110 "Unprocessable Content")
**Commit:** `feat(common): Idempotency-Key infrastructure, applied to POST /holds (W3·D3)` (squashed into one commit on `main`)

### Week 3 · Day 4: Bookings with a State-pattern lifecycle (planned Fri, Nov 27; done Tue, Nov 17, 2026)
**Status:** ✅ Done
**Built:**
- `BookingState` (sealed: `Held`, `PaymentPending`, `Confirmed`, `Terminal`) + `Booking.startPayment/confirm/expire/cancel/refund`, `isLive()`; `IllegalBookingTransitionException` (409); `BookingSeat.release()`
- `BookingService.createFromHold` + `POST /api/v1/holds/{holdId}/booking` (201 + Location / 200), `BookingView`; catalog `ShowSeatStatusService` + `ShowCatalogService.findSeatLabels`; `@EntityGraph` finders
- `BookingExpiryService.expireBatch` (`lockExpiredIds` native `FOR UPDATE SKIP LOCKED`) + `BookingExpirySweeper` (30 s, 100 × 20); `seatlock.bookings.*`
- `GET /api/v1/bookings/{publicId}`, `POST …/cancel` (`findForUpdateByPublicId`, PESSIMISTIC_WRITE)
- Fix: hold `expiresAt` truncated to ms (found by the conversion test)
- Verified locally: 1-min TTL → booking HELD → `Expired 1 unpaid bookings` → seat AVAILABLE; cancel → seat AVAILABLE
- Docs: ADR-008, architecture section 2e (state diagram), CORRECTNESS section 1f
- Tests: **313** (new: `BookingLifecycleTest` 32, `BookingConversionIntegrationTest` 6, `BookingExpiryIntegrationTest` 4, `BookingExpirySweeperTest` 3, `BookingApiIntegrationTest` 4, security matrix +4, `HoldServiceTest` +1)
**Files created/changed:**
- `booking\domain\{BookingState, IllegalBookingTransitionException, Booking, BookingSeat, BookingStatus, BookingRepository}.java`
- `booking\application\{BookingService, BookingView, BookingExpiryService, BookingExpirySweeper}.java`; `booking\api\BookingController.java`; `booking\hold\HoldService.java`
- `catalog\application\{ShowSeatStatusService, ShowCatalogService}.java`; `application.yml` (`seatlock.bookings`)
- Tests: `booking\domain\BookingLifecycleTest`, `booking\api\{BookingConversionIntegrationTest, BookingApiIntegrationTest}`, `booking\application\{BookingExpiryIntegrationTest, BookingExpirySweeperTest}`, `booking\hold\HoldServiceTest`, `identity\security\SecurityMatrixIntegrationTest`
- `docs\decisions\ADR-008-booking-lifecycle.md`, `docs\{architecture.md, CORRECTNESS.md}`, `progress.md`
**Decisions:**
- See Decisions Log #97–#103
**Metrics recorded:**
- 5 concurrent conversions → 1 booking; 2 sweepers → each booking expired once; 313 tests
**Problems & fixes:**
- Hold `expiresAt` had nanoseconds in the POST response but milliseconds in Redis/GET/booking → truncate at creation
- Open-in-view is off → loading `booking.getSeats()` outside a session would throw → `@EntityGraph`; but `FOR UPDATE` can't apply to an outer join → the locked finder loads seats lazily inside its transaction
- Concurrent conversions: the loser could see 404 (hold released) or 409 (seats HELD) → re-check for the winner's booking on every failure path
- Multi-catch `SeatLockException | RuntimeException` is a compile error (subclass) → `RuntimeException`
- New PowerShell window has no `$h` → log in again (`/api/v1/auth/login`)
**Carry-over:**
- D5 UI: "This show has started"; fresh `Idempotency-Key` per hold attempt
- JetBrains student license; IntelliJ vulnerable-dependency scan
**Interview topics covered (Q&A deferred until after v1.0.0):**
- State pattern vs enum switches; sealed hierarchies; making invalid transitions unrepresentable
- Two-phase reservation: ephemeral Redis hold → durable DB booking; where the oversell guarantee really lives
- Compare-and-set UPDATEs; flushing inside a transaction to surface constraint violations; translating constraint names
- Natural idempotency via unique business keys vs idempotency keys; handling every losing branch of a race
- `SELECT … FOR UPDATE SKIP LOCKED` job queues; batching; pessimistic vs optimistic locking per use case
- JPA: entity graphs, open-in-view, lazy loading, `FOR UPDATE` with outer joins
- Time precision across stores (ns `Instant` vs ms Redis vs µs Postgres)
**Commit:** `feat(booking): State-pattern booking lifecycle, hold → booking and expiry sweeper (W3·D4)` (squashed into one commit on `main`)

### Week 3 · Day 5: Rate-limited holds + frontend hold → booking flow (planned Sat, Nov 28; done Tue, Nov 17, 2026)
**Status:** ✅ Done
**Built:**
- Bucket4j 8.22.0 (`bucket4j_jdk17-lettuce`): `RateLimiter` (fail-open, lazy proxy, retry once on a fresh proxy, SmartLifecycle), `RateLimitInterceptor`, `@RateLimited`, `RateLimitProperties`, `RateLimitConfig`, `RateLimitWebConfig`, `RateLimitedException` → 429 + Retry-After; `@RateLimited("holds")` on `POST /holds`
- Frontend API: `ApiError.details` + `retryAfterSeconds`, `apiPostWithStatus` / `apiDelete` / `hasCode`, `api/holds.ts`, `lib/serverClock.ts`, `lib/useNow.ts`
- Seat map: `HoldPanel`, `holdStorage`, real Hold button / sign-in prompt, "✓ Your hold", started-show state, per-error messages, automatic retries with the same key
- Booking page `/bookings/:publicId` (RequireAuth): status, seats, total, time left to pay, Cancel, 10 s polling while unpaid, expired/cancelled/not-found
- Docs: ADR-009, architecture section 2f, CORRECTNESS section 1g, FAILURES F3
- Tests: **319** (new: `RateLimiterIntegrationTest` 3, `RateLimiterTest` 2, `HoldRateLimitIntegrationTest` 1); frontend `tsc` + ESLint clean
**Files created/changed:**
- `backend\pom.xml`; `common\ratelimit\*` (7 classes); `common\exception\GlobalExceptionHandler.java`; `booking\api\HoldController.java`; `application.yml` / `application-test.yml` (`seatlock.rate-limit`)
- Frontend: `src\api\{client.ts, holds.ts}`, `src\types\api.ts`, `src\lib\{serverClock.ts, useNow.ts}`, `src\features\seatmap\{HoldPanel.tsx, holdStorage.ts, SelectionSummary.tsx, SeatMap.tsx, SeatLegend.tsx, seats.ts}`, `src\pages\{ShowSeatsPage.tsx, BookingPage.tsx}`, `src\router.tsx`
- `docs\decisions\ADR-009-rate-limiting.md`, `docs\{architecture.md, CORRECTNESS.md, FAILURES.md}`, `progress.md`
**Decisions:**
- See Decisions Log #104–#110
**Metrics recorded:**
- 21st hold per minute → 429; Bucket4j on Lettuce 7 verified; 319 tests
**Problems & fixes:**
- Maven Central blocked from Claude's workspaces → coded against the documented 8.x API; compiled first time on the laptop
- `Connection is closed`: paused Spring test contexts stop the Lettuce client under a cached Bucket4j proxy → SmartLifecycle reset + self-healing retry
- `@WebMvcTest` slices failed (they load WebMvcConfigurer classes; limiter needs Redis) → `@Bean WebMvcConfigurer`
- CI-only `RateLimit-Remaining` missing → the GitHub merge of `main` (conflicts resolved "accept incoming") had dropped the `seatlock.rate-limit` block (and D4/D5 doc sections) → restored from the pre-merge commit (F3, #110)
- Hold countdown showed 1:00: the backend window still had `SEATLOCK_HOLDS_TTL=PT1M` from the sweeper check → remove env vars after experiments
- CI log links are too long / blocked for Claude → paste the `Results:` block or the specific log lines
**Carry-over:**
- Silence Mockito's dynamic agent warning (W6·D1); JetBrains student license; IntelliJ vulnerable-dependency scan
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Token bucket vs leaky bucket vs fixed/sliding windows; burst vs sustained rate; distributed buckets (CAS in Redis); fail-open vs fail-closed
- 429 + Retry-After + RateLimit headers; per-IP vs per-user limits; client IP behind a reverse proxy
- Library compatibility across major versions (provided-scope dependencies), lifecycle of pooled clients, self-healing connections
- Idempotent client retries (same key per attempt), server-clock offsets for countdowns, resumable UI state (sessionStorage) vs server truth
- Spring test-context caching/pausing; slice tests and which components they load
- Git: squash merges vs branch history, why "accept incoming" loses work, rebase --onto
**Commit:** `feat: rate-limited holds (Bucket4j) and frontend hold → booking flow with countdowns (W3·D5)` (squashed into one commit on `main`)

### Week 3 · Day 6: 200-user concurrency proof + Week 3 review (planned Mon, Nov 30; done Wed, Nov 18, 2026)
**Status:** ✅ Done
**Built:**
- Test support: `RealServerTest` (full app on a random Tomcat port), `TestUsers` (N users + real JWTs, no BCrypt), `ConcurrencyHarness` (start gate, every outcome kept, status/code counts, p50/p95/p99, `peakInFlight`, transport errors)
- Race A `HoldRace200IntegrationTest`: 200 users, 1 seat → 1 × 201, 199 × `SEATS_UNAVAILABLE`, Redis points at the winner, 1 hold record (×3, after a warm-up race)
- Race B `OverlappingHoldsRaceIntegrationTest`: 200 users, random 1–4 seats of a 12-seat block, then all winners book at once → no seat in two holds, Redis = API, all bookings 201, double-sold query 0 rows, `booking_seats` = `show_seats` HELD = winners' seats (×3, seed logged)
- Race C `LiveSeatIndexRaceIntegrationTest`: Redis/Lua/CAS bypassed, 200 transactions insert the same live seat → 1 commit, 199 × 23505 on `ux_booking_seats_live_seat`, losers fully rolled back
- Docs: CORRECTNESS section 1h (method, results, Postgres waiting-then-23505 behaviour, latency) and stale ⏳ rows flipped; benchmarks section 3 (contention latencies); section-sign characters replaced across docs and code comments
- Tests: **327** (8 new incl. repetitions)
**Files created/changed:**
- `backend\src\test\java\testsupport\{RealServerTest, TestUsers, ConcurrencyHarness}.java`
- `backend\src\test\java\com\seatlock\common\concurrency\ConcurrencyHarnessIntegrationTest.java`
- `backend\src\test\java\com\seatlock\booking\api\{HoldRace200IntegrationTest, OverlappingHoldsRaceIntegrationTest}.java`, `...\booking\domain\LiveSeatIndexRaceIntegrationTest.java`
- `docs\{CORRECTNESS.md, benchmarks.md, architecture.md, FAILURES.md}`, `docs\decisions\ADR-004-catalog-caching.md`, `GlobalExceptionHandler.java`, `AuthController.java` (comments only), `progress.md`
**Decisions:**
- See Decisions Log #111–#115
**Metrics recorded:**
- Races A/B/C (see Metrics table): 1 winner per seat, oversells = 0, DB index alone stops 199/200; contention p99 ~2 s on the laptop
**Problems & fixes:**
- Test in `testsupport` couldn't find `@SpringBootConfiguration` (searched upwards from a package outside `com.seatlock`) → test class moved under `com.seatlock.common.concurrency`, helpers stay in `testsupport`
- 3/200 requests without a response on the cold first race: TCP backlog (`accept-count` 100) overflowed, Windows reset the extras → `accept-count=512` for the race context + warm-up race; `transportErrors` now asserted empty
- `peakInFlight > 100` flaked (53 on a fast warm run) → ≥ 10%; correctness asserts unchanged
**Carry-over:**
- Silence Mockito's dynamic agent warning (W6·D1); JetBrains student license; IntelliJ vulnerable-dependency scan
**Week 3 review:**
- Plan D1–D6 all delivered, ~12 days ahead: live seat map with hold overlay, atomic Lua holds, idempotency keys, State-pattern bookings + expiry sweeper, Bucket4j rate limiting + frontend hold/booking flow, 200-user concurrency proof
- Oversell defence is now proven at every layer: Lua (atomic, all-or-nothing), `show_seats` compare-and-set, partial unique index (alone: race C); end to end with real HTTP (races A/B)
- ADRs to date: 001 dependency triage · 002 JWT · 003 Google sign-in · 004 catalog caching · 005 hosting · 006 seat holds · 007 idempotency keys · 008 booking lifecycle · 009 rate limiting
- Lessons: verify the switch is on before debugging the code behind it (F3); never "accept incoming" on squash-merge conflicts (#110); real-port tests find problems MockMvc can't (backlog, cold start)
- Risks for Week 4: (1) Razorpay test account/keys and Checkout.js on W4·D2; (2) webhook testing needs a public tunnel (cloudflared/ngrok) on W4·D5; (3) confirm-on-payment must reuse the holdId check + unique constraint under concurrency (reuse the harness)
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Testing concurrency: start gates (`CountDownLatch`), proving overlap, why a loop isn't a race, seeded randomness for replay
- MockMvc vs real server tests; TCP accept backlog; cold vs warm JVM (JIT, pools, script cache)
- Defence in depth: Redis as fast path, DB constraint as the guarantee; how Postgres unique indexes behave under concurrent inserts (wait, then 23505 or success on rollback)
- Invariant queries vs status-code assertions; latency percentiles under contention vs throughput benchmarks
**Commit:** `test(booking): 200-user concurrency proof (races A/B/C) and Week 3 review (W3·D6)` (squashed into one commit on `main`)

### Week 4 · Day 1: Payment gateway abstraction + create-order endpoint (planned Tue, Dec 1; done Wed, Nov 18, 2026)
**Status:** ✅ Done
**Built:**
- A: Flyway `V8__payments.sql` (attempts, unique gateway order id + attempt number, status CHECKs, partial unique `ux_payments_live_booking`); `Payment` entity (booking by id, `@Version`, guarded `markCaptured` / `markFailed` / `markRefunded` → 409 `INVALID_PAYMENT_TRANSITION`), `PaymentStatus`, `PaymentRepository`; `PaymentGateway` port, `GatewayName`, `CreateOrderCommand`, `GatewayOrder`, `PaymentGatewayException`, `MockGateway`, `PaymentProperties` + `PaymentGatewayConfig` (`seatlock.payments.gateway`, default `mock`)
- B: `Booking.requirePayable()`; `BookingPricing`, `PayableBooking`, `BookingExpiredException`; `BookingService.checkPayable` + `startPayment`; `PaymentService` + `PaymentOrderView`; `POST /api/v1/bookings/{publicId}/payment-order` (201 new / 200 open order; 404; 409 `BOOKING_EXPIRED` / `INVALID_BOOKING_TRANSITION`; 503 `PAYMENTS_UNAVAILABLE`)
- C: tests (pricing, payable states, service with mocks, API integration, 20-request race ×5); security matrix + OpenAPI checks; ADR-010; architecture section 2g; CORRECTNESS section 1i
- Repaired the W3·D6 squash merge (#6), which had dropped CORRECTNESS section 1h and progress.md's W3·D6 content (FAILURES F4)
- Tests: **364** (37 new incl. repetitions), 0 failures locally and in CI
**Files created/changed:**
- `backend\src\main\resources\db\migration\V8__payments.sql`; `application.yml` (`seatlock.payments.gateway`)
- `payment\domain\{Payment, PaymentStatus, PaymentRepository, IllegalPaymentTransitionException}.java`
- `payment\gateway\{PaymentGateway, GatewayName, CreateOrderCommand, GatewayOrder, PaymentGatewayException, MockGateway, PaymentProperties, PaymentGatewayConfig}.java`
- `payment\application\{PaymentService, PaymentOrderView}.java`; `payment\api\PaymentController.java`
- `booking\domain\Booking.java`; `booking\application\{BookingService, PayableBooking, BookingPricing, BookingExpiredException}.java`
- Tests: `payment\domain\{PaymentPersistenceTest, PaymentTest}`, `payment\gateway\MockGatewayTest`, `payment\application\PaymentServiceTest`, `payment\api\{PaymentOrderIntegrationTest, PaymentOrderRaceIntegrationTest}`, `booking\application\BookingPricingTest`, `booking\domain\BookingPayableTest`; `SecurityMatrixIntegrationTest`, `OpenApiDocsTest`
- `docs\decisions\ADR-010-payment-gateway.md`, `docs\{architecture.md, CORRECTNESS.md, FAILURES.md}`, `progress.md`
**Decisions:**
- See Decisions Log #116–#122
**Metrics recorded:**
- 20 concurrent "Pay now" → 1 order, 1 row (5/5 runs); 364 tests
**Problems & fixes:**
- #6 squash merge kept `main`'s older `progress.md` and `CORRECTNESS.md` (W3·D6 work lost on `main`) → restored both from `w3-d6` (`4e5d154`); FAILURES F4
- Task A was committed on `main` (branch created before the merge, then switched back) and `progress.md` was committed with `<<<<<<< Updated upstream` stash markers → `git branch -f w4-d1 HEAD`, `git branch -f main origin/main`, files restored; nothing reached `origin/main`
- Claude's `git diff` in the connected folder left `.git\index.lock` (no delete permission) → renamed, removed by user; Claude now uses `git --no-optional-locks` for read-only git
- A stale W3·D6 surefire report (`ConcurrencyHarnessIntegrationTest`, wrong package) still sits in `target` → `mvnw clean` (carry-over)
**Carry-over:**
- Silence Mockito's dynamic-agent warning (W6·D1); JetBrains student license; IntelliJ vulnerable-dependency scan
**Interview topics covered (Q&A deferred until after v1.0.0):**
- Ports and adapters / Strategy for payment providers; fakes vs mocks; keeping real providers out of tests and load tests
- Server-side pricing and why clients never send amounts; refusing inconsistent data instead of guessing
- Payment attempts vs payments; partial unique indexes for "at most one live row"; natural idempotency
- Not holding DB transactions/locks across network calls; serializing on a parent row (`SELECT … FOR UPDATE`); orphaned provider orders
- Transaction propagation (`MANDATORY`) as a module API contract; module boundaries with boundary records
- Squash-merge pitfalls: stale branches, conflict resolution that silently drops work, stash markers committed by accident
**Commit:** `feat(payment): payment gateway abstraction and create-order endpoint (W4·D1)` (squashed into one commit on `main`)
