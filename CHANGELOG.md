# Changelog

## v1.0.0 (2026-11-25)

First complete release of SeatLock: a flash-sale ticket booking platform where hundreds of people rush for the same
seats and **nobody ever gets the same seat twice**.

**Live:** https://seatlock-nu.vercel.app · API: https://seatlock-prajjwal.duckdns.org/swagger-ui.html ·
Walkthrough: [docs/media/seatlock-demo.gif](docs/media/seatlock-demo.gif)

### Features
- **Booking core:** event catalog with full-text search, live seat map (STOMP over WebSocket, Redis Pub/Sub across
  2 instances), atomic multi-seat holds (Redis Lua, 8-minute TTL) backed by a Postgres unique index, idempotent
  `POST /holds`, booking state machine, expiry sweeper.
- **Payments:** Razorpay (test mode) Orders + Checkout, signed callback verification, HMAC webhooks with dedupe,
  server-side pricing in paise, refunds through an outbox with retries, cancellation policy engine.
- **AI, with fallbacks:** Seat Finder (a sentence → validated filters → pure-Java search) and Refund Assistant (RAG
  over policy files; the engine computes the money, the AI only explains it, every ₹ is checked).
- **Accounts:** register/login with JWT (RS256), Google sign-in, roles (user / organizer / admin), one-click demo login.
- **UI:** React + TypeScript, dark mode, poster cards, phone layout.
- **Operations:** Azure VM with Docker Compose, Nginx (TLS, rate limits, load balancing), GitHub Actions CI/CD
  (GHCR image → SSH deploy), Prometheus + Grafana, JDK 25 AOT cache, nightly backups to Azure Blob with a restore
  drill, nightly demo-data upkeep.

### Measured
- **0 double-sold seats** in 200-user races over real HTTP, k6 flash sales and both locking strategies.
- Startup **−29 %** with the JDK 25 AOT cache (17.5 → 12.4 s median).
- Seat Finder intent parsing **34/40**, 0 wrong parses; policy retrieval recall@1 **30/30**.
- AI failure drills (bad key, 200 ms time limit, global cap, model that won't load, AI off): a fallback every time,
  **never a 5xx**.
- 642 tests (unit, Testcontainers, real-HTTP races, WireMock contracts), JaCoCo gate in CI.

Details: [README results](README.md#results-measured-not-estimated), [benchmarks](docs/benchmarks.md),
[CORRECTNESS](docs/CORRECTNESS.md), [FAILURES](docs/FAILURES.md).

### Dependencies
Spring Boot 4.1.3, Spring AI 2.0.3, Java 25. Security overrides: Tomcat 11.0.29 (9 CVEs in 11.0.27) and Guava 33.8.0-android (CVE-2026-102554). Remaining scanner findings (Logback, commons-codec, DJL) don't apply to how SeatLock uses them; reasons in ADR-001.

### Known limitations
- Seat Finder: 4 of 40 eval phrases hit its 4 s time limit (safe fallback to the manual form). Planned: its own
  ~6 s limit, then a re-run of the eval.
- Prod keeps the embedding model off (memory: ≈ 980 MB worst case per instance > the 850 MB budget); the Refund
  Assistant answers with its template + engine facts there.
- Payments are Razorpay **test mode** only; the VM is temporary and will be decommissioned after a few months.
