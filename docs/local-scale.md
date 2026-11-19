# Two instances locally (W5·D4)

`app-1` + `app-2` (same image, prod profile) behind Nginx, sharing the local Postgres and Redis. This is
the shape production gets on W5·D5. Seat events cross instances through the Redis Pub/Sub relay (ADR-014).

```
browser ──HTTP / WS──> lb (nginx:1.28, 127.0.0.1:8088) ──round robin──> app-1 :8080 ─┐
                                                                    └─> app-2 :8080 ─┴─> postgres, redis
checks only: app-1 = 127.0.0.1:8081, app-2 = 127.0.0.1:8082 (bypass the LB)
```

## Run

```powershell
cd D:\Prajjwal\Code\SeatLock
docker compose --env-file .env -f infra/docker-compose.local.yml -f infra/docker-compose.scale.yml up -d --build
docker compose --env-file .env -f infra/docker-compose.local.yml -f infra/docker-compose.scale.yml ps   # both apps (healthy)
# Frontend against the LB: frontend\.env.local  →  VITE_API_BASE_URL=http://localhost:8088   (then npm run dev)
# Stop the scale-out only:
docker compose --env-file .env -f infra/docker-compose.local.yml -f infra/docker-compose.scale.yml stop app-1 app-2 lb
```

Google sign-in doesn't work through 8088 (the OAuth client only allows `localhost:8080`); use the demo logins.

## Load balancer rules (`infra/nginx/local/seatlock-lb.conf`)
- Round robin, no sticky sessions: the API is stateless; a WebSocket stays on the instance that accepted it.
- `resolve` on the upstream servers (Docker DNS, 10 s): restarted containers with new IPs are picked up.
- Passive health: one failed connect removes an instance for 5 s; the request moves to the other one
  (`proxy_next_upstream error timeout`; never POST/DELETE).
- `/ws`: upgrade headers, 75 s read/send timeout (10 s STOMP heart-beats keep it busy).
- Keep-alive to the upstream: `Connection` is empty for plain requests and `upgrade` for handshakes.
- Responses carry `X-SeatLock-Instance` (app) and `X-Upstream` (Nginx).

## Checks
| # | Check | How | Expected |
|---|---|---|---|
| 1 | Round robin | `1..6 \| % { (irm http://localhost:8088/api/v1/system/version).instance }` | app-1 / app-2 alternate |
| 2 | WebSocket through Nginx | `curl.exe -i` upgrade to `http://localhost:8088/ws` | `101`, `X-Upstream` |
| 3 | Cross-instance relay | `.\load-tests\cross-instance-check.ps1` (and with `-Act`/`-Listen` swapped) | HELD then AVAILABLE on the other instance, versions n, n+1 |
| 4 | Two browsers on the LB | hold in one, watch the other (they may sit on different instances) | updates within ~1 s |
| 5 | Kill the viewer's instance | `docker stop seatlock-app-2` while a browser is connected | "Reconnecting…", then Live on app-1, map reloaded once; requests keep working |
| 6 | Bring it back | `docker start seatlock-app-2` | new requests reach app-2 again within ~10 s |

## Results (2026-11-19, laptop, Docker Desktop)
| What | Value |
|---|---|
| Idle memory per app container (limit 1200 MB) | ~383–387 MiB |
| Nginx / Postgres / Redis idle | ~8 MiB / ~100 MiB / ~13 MiB |
| Cross-instance HELD / AVAILABLE latency | *(from check 3)* |
| Reconnect after killing the viewer's instance | *(from check 5)* |
