# ADR-002: Stateless authentication with our own RS256 JWTs

**Date:** 2026-11-17 (W2·D1), amended W2·D2 · **Status:** Accepted

## Context
The API serves a React SPA on another origin (Vercel) and will run as **2 instances** behind
Nginx (W5). Authentication must work on any instance without shared session state, and must
later cover Google sign-in (W2·D3) and STOMP connections (W5·D1).

## Decision
1. **Stateless API.** `SessionCreationPolicy.STATELESS`, no cookies, no form/basic login.
   CSRF protection is **off**, which is safe only because credentials travel in the
   `Authorization` header (browsers never attach it automatically). If credentials ever move
   into a cookie, CSRF must be reconsidered.
2. **We issue our own access tokens** with Spring Security's `NimbusJwtEncoder`
   (no jjwt), and validate them as an OAuth2 resource server with `NimbusJwtDecoder`.
   Google login (W2·D3, **ADR-003**) also ends in *our* token, so the API trusts one issuer only.
3. **RS256 key pair**, not HS256: only the private key can sign; the public key only verifies.
   `kid` = JWK thumbprint, so a second key can be added for rotation later.
   Keys come from `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (prod: required, startup fails without);
   tests generate a throwaway pair. Pair mismatch or < 2048 bits fails at startup.
4. **Access token:** 8 h, **no refresh token** (W2·D2, decision #43). Claims: `iss=seatlock`,
   `aud=seatlock-api`, `sub=<user id>`, `iat`, `nbf`, `exp`, `jti`, `email`, `roles`. The decoder accepts **only RS256** (rejects
   `alg:none` / HS256 confusion) and checks `exp`/`nbf` (30 s skew, app `Clock`), `iss`, `aud`.
   `roles` → `ROLE_*` authorities; `scope` grants nothing.
5. **Passwords:** `DelegatingPasswordEncoder` with BCrypt only (`{bcrypt}` prefix, strength 10).
   Unknown or locked hashes never match (no exception). Passwords are 8 chars – 72 **bytes**
   (BCrypt's limit; longer would be truncated). No composition rules (NIST 800-63B).
6. **Error contract** (ProblemDetail, ADR in progress.md #12):

   | Situation | Status | `code` | `WWW-Authenticate` |
   |---|---|---|---|
   | No token on a protected route | 401 | `AUTHENTICATION_REQUIRED` | `Bearer` |
   | Bad / expired / forged token | 401 | `INVALID_TOKEN` | `Bearer error="invalid_token"` |
   | Wrong e-mail or password | 401 | `INVALID_CREDENTIALS` | — |
   | Signed in but not allowed | 403 | `ACCESS_DENIED` | — |
   | E-mail already registered | 409 | `EMAIL_TAKEN` | — |

   Exception messages are never returned; unknown e-mail and wrong password give identical
   bodies, and `DaoAuthenticationProvider` runs a dummy hash for unknown e-mails (timing).

7. **Authorization (W2·D2):**
   - Roles USER, ORGANIZER, ADMIN with a hierarchy **ADMIN ⊃ ORGANIZER ⊃ USER**
     (`RoleHierarchy` bean, used by both URL rules and method security).
   - Two independent gates: URL prefixes in `SecurityConfig` (`/api/v1/admin/**` → ADMIN,
     `/api/v1/organizer/**` → ORGANIZER) **and** `@PreAuthorize` on each controller. Everything
     not listed as public needs a valid token (deny by default).
   - **Ownership comes from the token** (`@CurrentUserId` = `sub`), never from a request
     parameter, so an organizer can only see or change their own events.
   - `SecurityMatrixIntegrationTest` pins who can call what with real signed tokens; every new
     protected endpoint adds rows there.
8. **Accounts (W2·D2):**
   - Public demo logins in Flyway `V5` (`demo.user@…`, `organizer.cinema@…`, password
     `seatlock-demo`): intentionally public, low-privilege; nightly reset from W8.
   - **ADMIN is never in Git.** `AdminBootstrap` creates it at startup from
     `SEATLOCK_ADMIN_EMAIL` / `SEATLOCK_ADMIN_PASSWORD` (12+ chars) if missing. It is
     create-only, safe with 2 instances starting together, and **never promotes an existing
     account** (otherwise whoever registered that e-mail first would become admin).

## Consequences
- No DB lookup per request; any instance can verify a token.
- **No refresh tokens** (user decision, W2·D2): the access token's 8 h lifetime is the session
  length, so a live demo never logs out mid-way. Trade-off: a stolen token works for up to 8 h
  and can't be revoked early. Accepted because there are no real users or real money
  (Razorpay test mode); mitigations are CSP/XSS hardening (W8·D1) and key rotation via `kid`.
- `409 EMAIL_TAKEN` allows account enumeration at sign-up. Accepted for UX; mitigated by
  rate limiting (W3·D5).
- Roles are read from the token, not the DB: a demoted user keeps the old role until the token
  expires (≤ 8 h). Accepted (admins are us; no user-management UI). If it ever matters:
  check `users.role` for ADMIN-only writes, or add a per-user "tokens valid after" timestamp.
- An expired token sent to a public endpoint is still rejected (standard resource-server
  behaviour); the SPA drops it and asks the user to sign in again.
