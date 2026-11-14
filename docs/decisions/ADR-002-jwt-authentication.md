# ADR-002: Stateless authentication with our own RS256 JWTs

**Date:** 2026-11-17 (W2·D1) · **Status:** Accepted

## Context
The API serves a React SPA on another origin (Vercel) and will run as **2 instances** behind
Nginx (W5). Authentication must work on any instance without shared session state, and must
later cover Google sign-in (W2·D3) and STOMP connections (W5·D1).

## Decision
1. **Stateless API.** `SessionCreationPolicy.STATELESS`, no cookies, no form/basic login.
   CSRF protection is **off**, which is safe only because credentials travel in the
   `Authorization` header (browsers never attach it automatically). If refresh tokens move
   into a cookie (W2·D2), CSRF must be reconsidered for that endpoint.
2. **We issue our own access tokens** with Spring Security's `NimbusJwtEncoder`
   (no jjwt), and validate them as an OAuth2 resource server with `NimbusJwtDecoder`.
   Google login (W2·D3) will also end in *our* token, so the API trusts one issuer only.
3. **RS256 key pair**, not HS256: only the private key can sign; the public key only verifies.
   `kid` = JWK thumbprint, so a second key can be added for rotation later.
   Keys come from `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (prod: required, startup fails without);
   tests generate a throwaway pair. Pair mismatch or < 2048 bits fails at startup.
4. **Access token:** 15 min. Claims: `iss=seatlock`, `aud=seatlock-api`, `sub=<user id>`,
   `iat`, `nbf`, `exp`, `jti`, `email`, `roles`. The decoder accepts **only RS256** (rejects
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

## Consequences
- No DB lookup per request; any instance can verify a token.
- A token can't be revoked before it expires (≤ 15 min). Accepted: short TTL + refresh token
  (W2·D2). Refresh-token rotation is a plan non-goal.
- `409 EMAIL_TAKEN` allows account enumeration at sign-up. Accepted for UX; mitigated by
  rate limiting (W3·D5).
- An expired token sent to a public endpoint is still rejected (standard resource-server
  behaviour); the SPA must refresh or drop it.
