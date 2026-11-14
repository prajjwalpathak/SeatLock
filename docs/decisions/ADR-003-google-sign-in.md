# ADR-003: Google sign-in that ends in our own JWT

**Date:** 2026-11-19 (W2·D3) · **Status:** Accepted · Builds on ADR-002

## Context
Users can sign in with Google as well as e-mail + password. Constraints:
- The API is **stateless** (no HTTP sessions) and will run as **2 instances** behind Nginx (W5),
  so the OAuth callback may land on a different instance than the one that started it.
- The SPA (`*.vercel.app`) and the API (`*.duckdns.org`) are on **different sites**.
- After sign-in the API must trust **one** token type only: ours (ADR-002).

## Decision
1. **Spring Security OAuth2 Client** (authorization-code flow, OIDC scopes `openid email profile`)
   in a **separate filter chain** (`@Order(1)`, only `/oauth2/**` and `/login/oauth2/**`).
   The API chain (`@Order(2)`) is unchanged and still token-only.
2. **No session for the in-flight sign-in.** The authorization request (`state`, `nonce`,
   PKCE `code_verifier`) is stored in **Redis** under its `state` for 5 min and read once (GETDEL).
   A `SL_OAUTH2_STATE` cookie (HttpOnly, SameSite=Lax, `Path=/login/oauth2/`, 5 min) must
   carry the same `state`, binding the callback to the browser that started it (**blocks login
   CSRF**). Stored as JSON fields, never Java-serialized. **PKCE (S256)** is on although we're a
   confidential client (OAuth 2.1 guidance). Google's tokens are discarded after sign-in.
3. **Token handoff: one-time code (option A2).** On success the API redirects to
   `{frontend}/auth/callback?code=<256-bit random>`; Redis keeps `SHA-256(code) → userId` for
   **60 s, single use**. The SPA POSTs it to `/api/v1/auth/oauth/exchange` and receives our JWT in
   the response body. Our JWT never appears in a URL, browser history or access log.
   `Referrer-Policy: no-referrer` on the redirect and the callback page.
4. **Account linking (option B1).** Only if Google says `email_verified = true`:
   an existing account with that e-mail (any provider) is signed into, unchanged; otherwise a new
   USER is created (`auth_provider = GOOGLE`, no password). Unverified or missing e-mail →
   refused (`GOOGLE_EMAIL_NOT_VERIFIED` / `GOOGLE_NO_EMAIL`).
5. **Redirect targets are fixed config** (`seatlock.frontend.base-url`, prod `FRONTEND_BASE_URL`),
   never taken from the request: no open redirect. Failures go to `{frontend}/login?error=CODE`.

## Flow
```mermaid
sequenceDiagram
    participant B as Browser (SPA)
    participant A as API
    participant R as Redis
    participant G as Google
    B->>A: GET /oauth2/authorization/google
    A->>R: SET authreq:<state> (5 min)
    A-->>B: 302 to Google (state, nonce, PKCE) + cookie SL_OAUTH2_STATE
    B->>G: sign in, consent
    G-->>B: 302 /login/oauth2/code/google?code&state
    B->>A: callback (+ cookie)
    A->>R: GETDEL authreq:<state> (cookie must match)
    A->>G: code + PKCE verifier → ID token (server to server)
    A->>A: verify ID token; find/link/create user (email_verified)
    A->>R: SET code:<sha256> → userId (60 s)
    A-->>B: 302 {frontend}/auth/callback?code=…
    B->>A: POST /api/v1/auth/oauth/exchange {code}
    A->>R: GETDEL code:<sha256>
    A-->>B: {accessToken, tokenType: Bearer, expiresIn}
```

## Alternatives rejected
- **Token in the redirect fragment** (`#token=`): simplest, but the 8 h token sits in history.
- **HTTP session for the OAuth state:** needs sticky sessions or Spring Session across 2 instances.
- **Refuse to link existing accounts:** safer but poor UX; verified e-mail is the industry norm.

## Consequences
- Redis becomes required for Google sign-in (already required for holds from W3).
- We link by e-mail, not by Google's stable `sub`: if a user changes their Google e-mail, the next
  Google sign-in creates/links by the new address. Acceptable for this project.
- Google app stays in **Testing** (≤ 100 test users), enough for a portfolio demo; basic scopes
  need no Google verification. Prod redirect URI is added on W2·D5.
