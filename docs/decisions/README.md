# Architecture Decision Records

Short records of the decisions that shaped SeatLock: context, options, decision, consequences.
The full decisions log (including small ones) lives in [progress.md](../../progress.md).

| ADR | Decision | Week |
|---|---|---|
| [001](ADR-001-dependency-vulnerability-triage.md) | Dependency vulnerability triage (override, not-applicable, test-only) | W1 |
| [002](ADR-002-jwt-authentication.md) | Stateless RS256 JWTs, no refresh tokens | W2 |
| [003](ADR-003-google-sign-in.md) | Google sign-in: separate filter chain, Redis state, one-time code handoff | W2 |
| [004](ADR-004-catalog-caching.md) | Measured Redis caching; seat map deliberately not cached | W2 |
| [005](ADR-005-hosting.md) | Azure VM (India South Central) + Vercel; CI/CD with GHCR and SSH deploy | W2 |
| [006](ADR-006-seat-holds.md) | Seat holds: Redis Lua + DB partial unique index as the final guard | W3 |
| [007](ADR-007-idempotency-keys.md) | Idempotency keys in Postgres for `POST /holds` | W3 |
| [008](ADR-008-booking-lifecycle.md) | Booking lifecycle as a sealed State pattern | W3 |
| [009](ADR-009-rate-limiting.md) | Bucket4j token buckets in Redis | W3 |
| [010](ADR-010-payment-gateway.md) | `PaymentGateway` port, server-side amounts, one live payment; Razorpay on RestClient (HTTP/1.1) | W4 |
| [011](ADR-011-payment-confirmation.md) | Signed callback + deduplicated webhook, one-transaction confirm, payment → booking lock order | W4 |
| [012](ADR-012-refunds.md) | Refund outbox, receipt idempotency, rules-based cancellation policy | W4 |
| [013](ADR-013-seat-locking-strategy.md) | `SeatLockingStrategy`: Redis Lua holds or optimistic Postgres claims | W4 |
| [014](ADR-014-realtime-seat-events.md) | Live seat events: STOMP topics, AFTER_COMMIT domain events, Redis versions, JWT on CONNECT | W5 |
| [015](ADR-015-monitoring.md) | Micrometer → Prometheus → Grafana; business meters, edge-hidden scrape endpoint, Grafana at /grafana/ | W6 |
| [016](ADR-016-ai-gateway.md) | AI gateway: Spring AI + Gemini behind a port, Resilience4j breaker + time limit, Bucket4j AI quotas, `ai_requests` audit | W7 |
| [017](ADR-017-policy-retrieval.md) | Policy RAG: local ONNX all-MiniLM-L6-v2 embeddings, own pgvector tables (HNSW), hash-skipped advisory-locked ingestion | W7 |
