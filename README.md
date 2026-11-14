# SeatLock

A flash-sale ticket booking platform where thousands of users rush for the same seats,
and **nobody ever gets the same seat twice**. Includes an AI Seat Finder and Refund Assistant
(AI interprets, code decides).

> 🚧 Work in progress. Full README (architecture diagram, benchmarks, demo) lands at v1.0.0.

## Stack

Java 25 · Spring Boot 4.1 · PostgreSQL 16 (pgvector) · Redis 7 · Spring Security (JWT + Google OAuth2) ·
WebSocket/STOMP · Razorpay (test mode) · Spring AI 2.0 · Testcontainers · k6 · Prometheus/Grafana ·
Docker · Nginx · GitHub Actions · Oracle Cloud ARM · React + TypeScript + Vite

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
