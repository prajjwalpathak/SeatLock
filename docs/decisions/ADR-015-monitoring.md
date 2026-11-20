# ADR-015: Metrics with Micrometer, Prometheus and Grafana

- **Status:** Accepted (W6·D2)
- **Context:** We need to *see* the core promise (zero oversells) and the system's health on prod with 2 instances,
  and we need numbers for the W6·D3 load tests. The VM has 3.8 GB RAM, of which the app stack's limits already
  take ~2.9 GB. Actuator health on port 8080 is used by the Docker HEALTHCHECK, `deploy.sh` and the CI smoke test.

## Decision
1. **Micrometer → Prometheus registry** (`micrometer-registry-prometheus`), `/actuator/prometheus` exposed in every
   profile; common tag `application=seatlock`; `http.server.requests` with **histogram buckets** so p95/p99 are
   computed in Prometheus across instances (client-side percentiles can't be aggregated).
2. **Same port, edge-hidden** instead of a separate management port: `/actuator/prometheus` is `permitAll` in
   Spring Security, Nginx answers 404 for `/actuator/*` except health, and the app ports are never published on
   the VM. A management port would also move `/actuator/health`, breaking the HEALTHCHECK, `deploy.sh` and the
   smoke test for no security gain on a private network. Other actuator endpoints stay ADMIN-only.
3. **Business meters (low cardinality: never a show, seat or user id as a tag):**
   - `seatlock.holds{strategy, outcome}`: `held`, `seats_taken` (lost the claim), `seats_unavailable` (DB pre-check),
     `hold_exists`, `booked_during_claim` (W6·D3, F12); registered at 0 on startup. Conflicts = everything but `held`.
   - `seatlock.oversell.blocked{guard}`: a booking the live-seat unique index rejected after Redis/CAS let it through.
   - `seatlock.seats.double_sold` (gauge): seats with more than one live claim, **counted from the data** every minute
     by `SeatInvariantMonitor` (same SQL as the race tests), not on each scrape; `-1` until the first count.
   - `seatlock.payments.captures{outcome}`: counted **after commit** (a rolled-back webhook isn't counted twice).
   - `seatlock.websocket.sessions` (gauge): open STOMP sessions, tracked as a set of ids (duplicate disconnects safe).
   - Cache hit/miss from Spring Cache statistics (`cache.gets{result}`, existing since ADR-004).
4. **Prometheus 3.13 (LTS)**, 15 s scrapes, 7 days / 1 GB retention, 256 MB limit; **Grafana 13.2**, 384 MB limit (192 MB starved it on first start, W6·D2 rollout), unified alerting off,
   data source and dashboard **provisioned from Git** (`infra/grafana/`), UI edits not kept (`allowUiUpdates: false`).
5. **Grafana on prod behind Nginx at `/grafana/`** (sub-path, `proxy_pass` through a variable so Nginx starts without
   it), own login with a generated admin password (`GRAFANA_ADMIN_PASSWORD`, `init-env-prod.sh`), sign-up and
   anonymous access off. Prometheus has no public route at all.

## Consequences
- Memory limits rise to ~3.5 of 3.8 GB (limits, not usage; idle use was ~1.5 GiB). Swap (2 GB) is the safety net;
  W6·D3 load tests stay on the laptop, so prod never runs near the limits.
- `/actuator/prometheus` is unauthenticated: anyone *on the Docker network* can read metrics. Acceptable: nothing
  else runs there, and meters carry no personal data.
- Rejected: Spring Boot Admin / Grafana Cloud (extra account, outside the locked stack), pushgateway (pull model is
  simpler), per-seat or per-user tags (unbounded time series), counting oversells only in code (the gauge reads the
  data, so it would also catch a bug in the code path that should have counted).
