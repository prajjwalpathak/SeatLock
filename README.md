# SeatLock

[![CI](https://github.com/prajjwalpathak/SeatLock/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/prajjwalpathak/SeatLock/actions/workflows/ci.yml)
[![Deploy](https://github.com/prajjwalpathak/SeatLock/actions/workflows/deploy.yml/badge.svg)](https://github.com/prajjwalpathak/SeatLock/actions/workflows/deploy.yml)

A flash-sale ticket booking platform where thousands of users rush for the same seats,
and **nobody ever gets the same seat twice**. Includes an AI Seat Finder and Refund Assistant
(AI interprets, code decides).

> 🚧 Work in progress. Full README (architecture diagram, benchmarks, demo) lands at v1.0.0.

**Live demo:** https://seatlock-nu.vercel.app · API: https://seatlock-prajjwal.duckdns.org/swagger-ui.html
(deployment runbook: [docs/deploy.md](docs/deploy.md))

## What works today (Weeks 1–6)

- **Catalog**: events, shows, live seat map (Redis hold overlay), full-text search, measured caching
- **Seat holds**: atomic multi-seat holds (Redis Lua, or optimistic Postgres claims behind `SeatLockingStrategy`),
  idempotency keys, rate limits; a DB partial unique index makes overselling impossible (200-user races, 0 double-sold)
- **Bookings**: State-pattern lifecycle, hold → booking, expiry sweeper
- **Payments (Razorpay test mode)**: server-side amounts, Checkout.js, signed checkout verification, deduplicated
  HMAC webhooks, one-transaction confirm proven under callback/webhook/sweeper races, automatic refunds via an outbox,
  cancellation policy (≥ 48 h 100 %, ≥ 24 h 50 %)
- **Live seat map**: STOMP over WebSocket (JWT on CONNECT, subscription rules), seat events published after commit,
  relayed across instances through Redis Pub/Sub with per-show versions; the browser resyncs on gaps or reconnects
- **Two instances in production** behind Nginx (round robin, WebSocket upgrade), zero-downtime rolling deploys with
  automatic rollback; failure drills (instance loss, crash mid-booking, Redis/Postgres restarts) measured in FAILURES F8
- **My bookings** page for signed-in users
- **Load-tested (k6, local 2-instance stack)**: 500 users rushing 10 seats → **oversells = 0 in every sale** (k6 per-seat
  thresholds + DB invariant); browse p95 6–11 ms at 50 VUs; Redis holds **336 holds/s** vs 233 optimistic (p99 188 vs
  310 ms); virtual threads measured and left off ([docs/benchmarks.md](docs/benchmarks.md))
- **Observability**: Micrometer business metrics (holds by outcome, oversells blocked, double-sold seats counted from the
  data) → Prometheus → Grafana dashboard provisioned from Git, live on prod at `/grafana/`
- **Tuned from measurements**: G1 instead of the JVM's Serial default in small containers, Redis timeout sized for a
  saturated CPU, partial index for the expiry sweeper (34.5 → 0.14 ms at 205k bookings); isolation levels pinned by tests
- **Tests**: 523 (unit, Testcontainers integration, 200-user races over real HTTP, WireMock contract tests);
  coverage **94 % lines / 81 % branches**, enforced in CI by a JaCoCo gate (≥ 85 % lines, ≥ 80 % branches)
- Decisions: [docs/decisions](docs/decisions/README.md) · correctness proofs: [docs/CORRECTNESS.md](docs/CORRECTNESS.md) ·
  failure drills: [docs/FAILURES.md](docs/FAILURES.md)

## Stack

Java 25 · Spring Boot 4.1 · PostgreSQL 16 (pgvector) · Redis 7 · Spring Security (JWT + Google OAuth2) ·
WebSocket/STOMP · Razorpay (test mode) · Spring AI 2.0 · Testcontainers · k6 · Prometheus/Grafana ·
Docker · Nginx · GitHub Actions · Azure VM · Vercel · React + TypeScript + Vite

## Repository layout

| Folder        | Purpose                                                            |
| ------------- | ------------------------------------------------------------------ |
| `backend/`    | Spring Boot application                                            |
| `frontend/`   | React + TypeScript SPA                                             |
| `infra/`      | Docker Compose, Nginx, Prometheus, Grafana, VM scripts             |
| `load-tests/` | k6 scripts                                                         |
| `docs/`       | Architecture, correctness proofs, failure drills, benchmarks, ADRs |

## Demo accounts

| Role | E-mail | Password |
|---|---|---|
| USER | `demo.user@seatlock.test` | `seatlock-demo` |
| ORGANIZER | `organizer.cinema@seatlock.test` | `seatlock-demo` |
| ADMIN | not public: created at startup from `SEATLOCK_ADMIN_EMAIL` / `SEATLOCK_ADMIN_PASSWORD` | — |

Demo data is reset nightly from W8 onward. You can also **Continue with Google** (the Google app
is in testing mode, so only test users added in Google Auth Platform can use it).

## Local setup

_Full guide coming soon._ For Google sign-in locally: create a **Web application** OAuth client in
Google Auth Platform with redirect URI `http://localhost:8080/login/oauth2/code/google`, add
yourself as a test user, and put `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` in the repo-root `.env`
(see `.env.example`).
