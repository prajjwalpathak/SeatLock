# ADR-005: Hosting: single Azure VM (Docker Compose) + Vercel

**Date:** 2026-11-16 · **Status:** Accepted

## Context

SeatLock needs a public, always-on demo (resume link) for roughly 2–3 months, on student money:
Azure for Students credit (USD 100) and free tiers. The backend is Spring Boot + Postgres + Redis,
later Prometheus/Grafana. Benchmarks and load tests are run locally (see `benchmarks.md`), not in prod.

## Options considered

| Option | Monthly cost | Notes |
|---|---|---|
| **A. Azure VM + Docker Compose + Nginx/Let's Encrypt** | ~USD 24 running, ~USD 6 deallocated | Full control; one box runs everything incl. monitoring; real Linux/TLS ops experience |
| B. Azure Container Apps + Neon + Upstash | ~USD 0 (scale-to-zero, free grants) | 20–40 s JVM cold start; 3 providers; no self-hosted Prometheus; free-tier limits break load tests |
| C. Render free | USD 0 | 512 MB / 0.1 CPU too small for Spring Boot; free Postgres expires after 30 days |
| D. Laptop + tunnel (ngrok/Cloudflare) | USD 0 | Demo only works while the laptop is on; exposes the laptop |

## Decision

**Option A.** Backend on one Azure VM, frontend on Vercel (free, Git-connected).

| Item | Value |
|---|---|
| Region | **India South Central** (the only India region allowed by the student-subscription region policy; Central India is blocked) |
| Size | **Standard_B2als_v2**, x64 (AMD), 2 vCPU, 4 GiB, burstable; ~USD 0.025/h |
| Why not ARM (B2pls_v2) | `NotAvailableForSubscription` for this subscription; x64 also keeps CI builds emulation-free |
| Disk | 30 GiB Standard SSD (E4 tier) |
| Public IP | Standard, static → DuckDNS `seatlock-prajjwal.duckdns.org` |
| Edge | Nginx 1.28: TLS (Let's Encrypt, auto-renew), HSTS, gzip, login rate limit, actuator hidden |
| Frontend | Vercel, `https://seatlock-nu.vercel.app`, `VITE_API_BASE_URL` set at build time |
| Total | ~USD 24/month → about 4 months of credit; budget alert at USD 30 |

## Consequences

- **Burst credits:** B-series VMs run at a 30–40 % CPU baseline and burst on banked credits.
  Fine for demo traffic; **load tests (Week 6) run locally**, never against this VM, and
  deallocating the VM discards banked credits.
- **Single point of failure:** no HA, no managed backups. Acceptable for a demo; `pg_dump`
  before risky changes (see `deploy.md`).
- **Planned decommission** after ~2–3 months: delete the resource group (stopping alone still
  bills ~USD 6/month for disk + IP). The resume keeps "deployed on Azure with Docker Compose,
  Nginx/TLS and GitHub Actions; decommissioned after the demo period".
- **CI/CD (W2·D6):** images are built once by GitHub Actions and pulled from GHCR; deploys go over SSH with a
  forced-command key (`deploy.sh`: health-wait + automatic rollback). This requires SSH open to *Any*
  (runner IPs are random), mitigated by key-only auth, fail2ban and the single-purpose CI key. Alternatives
  (VM pull loop, Azure run-command via OIDC, Tailscale) were rejected for no deploy feedback / student-tenant
  limits / extra accounts.

## Update W5·D5: two app instances on the VM
- `app-1` + `app-2` (same GHCR image, 900 MB limit each, ~370 MiB idle measured) behind Nginx upstream `seatlock_app`
  (round robin, `resolve`, passive health, `/ws` upgrade). Budget: 2 × 900 + 768 (Postgres) + 256 (Redis) + Nginx/certbot
  ≈ 2.9 GB of 3.8 GB; 2 GB swap stays as a safety net.
- `deploy.sh` is a rolling deploy (app-1, wait healthy, app-2, wait healthy; any failure rolls both back), so deploys no
  longer take the API down. CI's smoke test requires both instances to report the new revision.
- Known gap: a request already sent over a keep-alive connection to the instance being stopped can still fail (502);
  Nginx retries only failed connects and never POST/DELETE once sent.
