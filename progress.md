# SeatLock: Progress Log

> Updated at the end of every day (when I say "next"). Keep this file in the repo root and re-upload it to Project knowledge after each update.

## Current Status
- **Current day:** Week 2 · Day 4 (not started, planned Fri Nov 20): Redis caching (`@Cacheable` events/layouts), eviction on organizer updates, cache metrics; organizer endpoint: create show
- **Last completed:** Week 2 · Day 3: Google sign-in → our JWT; frontend login/register/callback, protected routes, logout (planned Thu Nov 19, done Sat Nov 14)
- **Live URLs:** Backend — · Frontend — · Swagger — (local: http://localhost:8080/swagger-ui.html) · Grafana —
- **Repo:** https://github.com/prajjwalpathak/SeatLock (public)
- **Carry-overs:** try Oracle Cloud signup with a family Visa/Mastercard credit card (hosting decision by **W2·D4, Fri Nov 20**) · JetBrains student license (via GitHub Student Developer Pack) · record JaCoCo numbers · IntelliJ vulnerable-dependency scan for the security + OAuth2 client deps · add the prod redirect URI to the Google client on W2·D5
- **Schedule:** **~5 days ahead** (W2·D3 done Sat Nov 14 instead of Thu Nov 19); target finish **Mon, Jan 4, 2027**; weeks run Tue–Mon
- **Top risk:** hosting decision by **W2·D4 (Fri Nov 20)**; first live deploy is W2·D5

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
| 9 | 2026-11-10 | **One git commit per day**, made at "next" after the progress.md update | Clean, day-aligned history |
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

## Metrics Captured (resume numbers)
| Metric | Value | How measured | Date |
|---|---|---|---|
| Event list query (Q1) after targeted index | 3.31 → **0.66 ms (~5×)**; buffers 251 → **121 (−52%)** | `EXPLAIN (ANALYZE, BUFFERS)`, seeded DB, warm cache (`docs/explain/`) | 2026-11-14 |
| Event detail shows query (Q2) | 1.11 → **0.15 ms**; buffers 24 → 10 | same | 2026-11-14 |
| Test coverage (JaCoCo) | 79% instructions, ~80% lines (309/384), 60% branches; 33 tests | `mvnw verify` | 2026-11-14 |

## Accounts & Config (no secrets here, only where they live)
| Item | Status | Where stored |
|---|---|---|
| GitHub repo | ✅ Created (public) | github.com/prajjwalpathak/SeatLock |
| Oracle Cloud VM | ⏳ Try signup with a family Visa/Mastercard credit card (deadline W2·D4) | — |
| Azure for Students | ✅ Active ($100, 12 months; fallback host; budget alert $15/month; no resources created yet) | portal.azure.com (DTU email) |
| AWS | ❌ Free plan already used up; not used for this project | — |
| DuckDNS domain | — (W2·D5) | — |
| Razorpay test keys | — (W4·D2) | `.env` (git-ignored) |
| Google OAuth client | ✅ Web client "SeatLock local" (Testing mode, test user added); redirect `http://localhost:8080/login/oauth2/code/google`; prod URI on W2·D5 | `.env` (`GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`, git-ignored) |
| Gemini/Groq API key | — (W7·D1) | `.env` (git-ignored) |
| Vercel project | — (W2·D5) | — |
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
- ADR-002; architecture §2a (login sequence diagram)
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
- README "Demo accounts"; Swagger "Try it" instructions; ADR-002 §7–§8 + role-staleness consequence; architecture §2a protected-routes table
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
- ADR-003 (with sequence diagram); architecture §2a; README demo + local Google setup
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

