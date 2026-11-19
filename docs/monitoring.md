# Monitoring (W6·D2, ADR-015)

Micrometer metrics → Prometheus (scrapes `/actuator/prometheus` every 15 s) → Grafana dashboard **SeatLock overview**
(`infra/grafana/dashboards/seatlock.json`, provisioned from Git).

## Run locally

```powershell
cd D:\Prajjwal\Code\SeatLock
docker compose --env-file .env -f infra/docker-compose.local.yml up -d           # postgres, redis, prometheus, grafana
# optional, 2 instances: add -f infra/docker-compose.scale.yml ... up -d --build
```
- Prometheus: http://localhost:9090 → Status → Targets: `ide` (IntelliJ app on 8080), `app-1`, `app-2` (scale stack).
  Targets that aren't running show DOWN; that's expected.
- Grafana: http://localhost:3000, user `admin`, password `GRAFANA_ADMIN_PASSWORD` from `.env` (default `admin`).
  The home dashboard is SeatLock overview. **Edits in the UI aren't kept**: export the JSON and commit it.

## Meters

| Meter (Prometheus name) | Type | Meaning |
|---|---|---|
| `seatlock_seats_double_sold` | gauge | Seats with > 1 live booking, counted from the DB every minute. **Must be 0** (`-1` = not counted yet) |
| `seatlock_oversell_blocked_total{guard}` | counter | Bookings the live-seat unique index rejected (Redis/DB disagreed): oversells the DB stopped |
| `seatlock_holds_total{strategy,outcome}` | counter | Hold attempts: `held`, `seats_taken`, `seats_unavailable`, `hold_exists` |
| `seatlock_payments_captures_total{outcome}` | counter | Captures after commit: `confirmed`, `already_done`, `captured_booking_lost`, `needs_review` |
| `seatlock_websocket_sessions` | gauge | Open STOMP sessions per instance |
| `cache_gets_total{cache,result}` | counter | Spring Cache hits / misses (eventDetail, eventSearch) |
| `http_server_requests_seconds_bucket{uri,method,status}` | histogram | Latency per route; p95/p99 via `histogram_quantile` |
| `hikaricp_connections_active/pending`, `jvm_memory_used_bytes` | gauge | Pool and heap per instance |

## Useful queries

```promql
max(seatlock_seats_double_sold)                                                       # oversells (0)
sum(rate(seatlock_holds_total{outcome!="held"}[5m])) / sum(rate(seatlock_holds_total[5m]))   # conflict ratio
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[1m])))
```

## Prod

Grafana: `https://seatlock-prajjwal.duckdns.org/grafana/` (user `admin`, password `GRAFANA_ADMIN_PASSWORD` in
`infra/.env.prod`). Prometheus has no public route; to look at it directly, tunnel:
`ssh -i $HOME\.ssh\azure_seatlock -L 9091:localhost:9091 ubuntu@seatlock-prajjwal.duckdns.org` and on the VM
`docker run --rm --network seatlock-prod_default -p 127.0.0.1:9091:9091 alpine/socat TCP-LISTEN:9091,fork TCP:prometheus:9090`
(only when needed). Rollout steps: `docs/deploy.md` section 3c.
