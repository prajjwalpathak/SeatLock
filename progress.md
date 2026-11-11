# SeatLock: Progress Log

> Updated at the end of every day (when I say "next"). Keep this file in the repo root and re-upload it to Project knowledge after each update.

## Current Status
- **Current day:** Week 1 · Day 3 (not started): data model (ERD), Flyway `V1__init.sql`, JPA entities + repositories
- **Last completed:** Week 1 · Day 2: App skeleton (Wed, Nov 11, 2026)
- **Live URLs:** Backend — · Frontend — · Swagger — · Grafana —
- **Repo:** https://github.com/prajjwalpathak/SeatLock (public)
- **Carry-overs:** try Oracle Cloud signup with a family Visa/Mastercard credit card (hosting decision by **W2·D4, Fri Nov 20**) · JetBrains student license (via GitHub Student Developer Pack)
- **Schedule:** on track (revised target finish **Mon, Jan 4, 2027**; weeks run Tue–Mon)

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

## Metrics Captured (resume numbers)
| Metric | Value | How measured | Date |
|---|---|---|---|
| — | — | — | — |

## Accounts & Config (no secrets here, only where they live)
| Item | Status | Where stored |
|---|---|---|
| GitHub repo | ✅ Created (public) | github.com/prajjwalpathak/SeatLock |
| Oracle Cloud VM | ⏳ Try signup with a family Visa/Mastercard credit card (deadline W2·D4) | — |
| Azure for Students | ✅ Active ($100, 12 months; fallback host; budget alert $15/month; no resources created yet) | portal.azure.com (DTU email) |
| AWS | ❌ Free plan already used up; not used for this project | — |
| DuckDNS domain | — (W2·D5) | — |
| Razorpay test keys | — (W4·D2) | `.env` (git-ignored) |
| Google OAuth client | — (W2·D3) | `.env` (git-ignored) |
| Gemini/Groq API key | — (W7·D1) | `.env` (git-ignored) |
| Vercel project | — (W2·D5) | — |
| Local Postgres/Redis creds | ✅ | `.env` (git-ignored); keys documented in `.env.example` |

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
