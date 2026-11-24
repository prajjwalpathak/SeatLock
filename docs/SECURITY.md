# SeatLock: Security hardening (W8·D1)

What protects the public deployment, who owns each control, and how to re-check it.

## 1. Response headers

One owner per header, so prod never sends a header twice (the W5·D5 carry-over: HSTS came from both Spring and Nginx).

| Header | Value | Owner | Why there |
|---|---|---|---|
| `Strict-Transport-Security` | `max-age=31536000` | **Nginx** | TLS ends at Nginx, which also serves Grafana and its own error pages. Spring's HSTS is off; Nginx hides any upstream copy |
| `X-Content-Type-Options` | `nosniff` | Spring (tests, local) **and** Nginx (whole host) | Nginx hides the upstream copy and adds its own: one value |
| `Referrer-Policy` | `strict-origin-when-cross-origin` (`no-referrer` on the Google sign-in redirect) | **Spring** | Nginx adds the default only when the upstream sent none (`map $upstream_http_referrer_policy`) |
| `X-Frame-Options` | `DENY` | Spring (default) | The API is never framed |
| `X-XSS-Protection` | `0` | Spring (default) | Turns off the legacy browser XSS auditor, which could itself be abused; CSP is the real control |
| `Cache-Control` | `no-cache, no-store, max-age=0, must-revalidate` | Spring (default) | Per-user data is never cached by browsers or proxies |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=(), usb=()` | Spring | No browser features are needed |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` | Spring, **`/api/**` only** | JSON never loads anything; Swagger UI (outside `/api/`) still works |

The frontend's headers are Vercel's (separate origin) and out of scope here.

Code: `identity.security.SecurityHeaders` (both filter chains), `infra/nginx/templates/seatlock.conf.template`.
Test: `SecurityHeadersIntegrationTest`.

## 2. CORS

- Exact origins only. `CorsProperties` refuses `*`, wildcards, paths and trailing slashes at startup (`CorsPropertiesTest`).
- Methods `GET, POST, DELETE` (the only ones the API maps); headers `Content-Type, Authorization, Idempotency-Key, X-Request-Id`.
- `allowCredentials(false)`: the JWT goes in a header, there are no cookies on `/api/**`.
- Unknown origin → 403 (`CorsIntegrationTest`).

## 3. Input limits

| Limit | Value | Where |
|---|---|---|
| Request body | 1 MB → **413 `PAYLOAD_TOO_LARGE`** | Nginx `client_max_body_size` + `RequestBodyLimitFilter` (by `Content-Length`, before parsing) |
| Chunked bodies | 1 MB in prod (Nginx); locally bounded only by the per-field limits | known gap, accepted (no client sends chunked bodies) |
| Request line + headers | 8 KB | `server.max-http-request-header-size` |
| Free text | 300 chars for AI input, 100 for names / search, 254 e-mail, 72 bytes password | `@Size` on every request DTO (audited W8·D1) |
| Lists / maps | hold ≤ 50 seat ids (business rule: 6), show prices ≤ 10 sections | `@Size` on `CreateHoldRequest`, `CreateShowRequest` |
| Rates | login/register 10/min/IP (Nginx); holds, Seat Finder, AI per IP + per user (Bucket4j) | ADR-009, ADR-016 |

## 4. Secret scan

- `gitleaks` (Docker image `zricethezav/gitleaks`, pinned) over the **full Git history** in CI (`Secret scan (gitleaks)` job, fetch-depth 0, redacted output) and on demand locally (command below).
- `.gitleaks.toml`: default rules + allow-list of the reviewed `.env*.example` files only.
- `.gitleaksignore`: individual reviewed findings by fingerprint (commit:file:rule:line), so a rule is never loosened for a whole file.
- Public on purpose (not secrets): the demo password `seatlock-demo` and its BCrypt hash in `V5__demo_users.sql`; test-only values in `application-test.yml` / `PaymentFixtures` (`test-key-secret`, `test-webhook-secret`); throwaway RSA keys are generated per test run, never stored.
- Never in Git: `.env`, `infra/.env.prod` (generated on the VM, #66), `*.pem`, `*.key` (`.gitignore`).
- Checks on secret files print **counts or names, never values** (`grep -c`, `cut -d= -f1`); a SAS **URL** is a secret (it carries the signature).
- If a real secret is ever found: **rotate it first** (deleting the commit doesn't help, the repo is public), then decide whether to rewrite history.

```powershell
cd D:\Prajjwal\Code\SeatLock
docker run --rm -v "${PWD}:/repo" --entrypoint sh zricethezav/gitleaks:v8.30.1 -c "git config --global --add safe.directory /repo && gitleaks git /repo --config /repo/.gitleaks.toml --gitleaks-ignore-path /repo/.gitleaksignore --redact --verbose"
```

Result (W8·D1): see section 6.

## 5. Dependencies

Triage policy and findings: [ADR-001](decisions/ADR-001-dependency-vulnerability-triage.md) (revisited W8·D1).

## 6. Check log

| Date | Check | Result |
|---|---|---|
| 2026-11-23 | gitleaks v8.30.1, full history (145 commits, 3.95 MB) | 2 findings, both `generic-api-key` on the same line: a made-up UUID used as an `Idempotency-Key` in `IdempotencyInterceptorTest` (original commit + its squash). False positive → `.gitleaksignore`. Re-run: 0 |
| 2026-11-24 | Headers on prod after the W8·D2 rollout (`curl -sI` on `/api/v1/system/time` and `/grafana/login`) | API: exactly one each of HSTS, nosniff, `X-Frame-Options: DENY`, Referrer-Policy, Permissions-Policy, plus the CSP. Grafana: one HSTS, one nosniff, Referrer-Policy from the Nginx default. Duplicate HSTS (W5·D5) closed |
| 2026-11-24 | Backup SAS token | Copied the **SAS URL** (with signature) into `BACKUP_BLOB_URL`; a check printed it into a chat. Rotated key1 (invalidates every SAS signed with it), new token from the "Blob SAS token" field only; checks now count matches instead of printing values |
