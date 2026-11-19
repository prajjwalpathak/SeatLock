# Deployment runbook

Production = **one Azure VM** (backend stack in Docker Compose) + **Vercel** (frontend).
Why this setup: [ADR-005](decisions/ADR-005-hosting.md).

| What | Where |
|---|---|
| API | `https://seatlock-prajjwal.duckdns.org` (Swagger: `/swagger-ui.html`) |
| Frontend | `https://seatlock-nu.vercel.app` |
| VM | Azure `seatlock-vm` (India South Central, B2als_v2), user `ubuntu`, key `~\.ssh\azure_seatlock` |
| Stack | `infra/docker-compose.prod.yml`: nginx · app-1 · app-2 · postgres · redis · certbot |
| Backend image | `ghcr.io/prajjwalpathak/seatlock-backend:sha-<12>` (public), built by GitHub Actions; running tag = `APP_IMAGE` in `.env.prod` |
| Version check | `GET /api/v1/system/version` → `{"revision":"<git sha>"}` |
| Secrets | `~/seatlock/infra/.env.prod` on the VM only (mode 600, never in Git) |

```
git push main ─► CI (tests, coverage, lint, build) ─► Deploy: build image ─► GHCR
                                                        └─► SSH (restricted key) ─► VM: deploy.sh ─► healthy? else rollback
Browser ──HTTPS──► Vercel (React static)
   │
   └──HTTPS──► VM :443 Nginx ──► app-1 / app-2 :8080 ──► postgres:5432 / redis:6379   (private Compose network)
                 :80 → 301 to HTTPS; /.well-known/acme-challenge → certbot webroot
```

All commands below run **on the VM** unless marked PowerShell. Shortcut used throughout:

```bash
cd ~/seatlock
alias dc='docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml'
```

---

## 1. First-time setup (done 2026-11-16)

1. **VM** (Azure portal): Ubuntu 24.04 x64, Standard_B2als_v2, SSH key auth, inbound 22/80/443,
   then restrict the SSH rule's source to *My IP address*. Budget alert USD 30/month.
2. **DNS**: DuckDNS `seatlock-prajjwal` → VM public IP.
3. **Docker + 2 GB swap**: official Docker apt repo (`docker-ce`, `docker-compose-plugin`),
   `usermod -aG docker ubuntu`, `/swapfile` in `/etc/fstab`.
4. **Infra files** (PowerShell, repo root). The VM needs only `infra/` (Compose, Nginx, scripts); the backend
   arrives as an image from GHCR. (On the first day the whole `backend/` was copied and built on the VM.)
   ```powershell
   tar -czf $env:TEMP\seatlock-infra.tgz infra
   scp -i $HOME\.ssh\azure_seatlock $env:TEMP\seatlock-infra.tgz ubuntu@<VM-IP>:~/
   ```
   ```bash
   mkdir -p ~/seatlock && tar -xzf ~/seatlock-infra.tgz -C ~/seatlock && chmod +x ~/seatlock/infra/scripts/*.sh
   ```
5. **Secrets**: `bash infra/scripts/init-env-prod.sh` generates the DB password, a new RS256 JWT pair
   and the admin password (printed once). Then `nano infra/.env.prod` for the rest:
   e-mails, `GOOGLE_*`, `FRONTEND_BASE_URL`, `CORS_ALLOWED_ORIGINS`.
   Check none are empty: `grep -E '^[A-Z_]+=$' infra/.env.prod || echo "all values filled"`.
6. **Google OAuth**: add redirect URI `https://seatlock-prajjwal.duckdns.org/login/oauth2/code/google`.
7. **TLS certificate** (once, before Nginx exists, so port 80 is free):
   ```bash
   dc run --rm -p 80:80 --entrypoint certbot certbot certonly --standalone \
     -d seatlock-prajjwal.duckdns.org --email <email> --agree-tos --no-eff-email
   ```
8. **Start**: `dc up -d` → `dc ps` (app-1 and app-2 *healthy*, then nginx starts). The app image is pulled from GHCR.
9. **Vercel**: import the GitHub repo, Root Directory `frontend`, env `VITE_API_BASE_URL=https://seatlock-prajjwal.duckdns.org`.
   `frontend/vercel.json` rewrites every path to `index.html` (SPA deep links, `/auth/callback`).

## 2. Smoke test (PowerShell)

```powershell
$B = "https://seatlock-prajjwal.duckdns.org"
curl.exe -sI http://seatlock-prajjwal.duckdns.org | Select-String "HTTP|Location"   # 301 → https
curl.exe -s "$B/actuator/health/readiness"                                         # {"status":"UP"}
curl.exe -s -o NUL -w "%{http_code}`n" "$B/actuator/metrics"                        # 404 (hidden at edge)
curl.exe -s -o NUL -D - -H "Origin: https://seatlock-nu.vercel.app" "$B/api/v1/events?limit=1" |
  Select-String "HTTP/|Access-Control-Allow-Origin"                               # 200 + the Vercel origin
1..20 | % { curl.exe -s -o NUL -w "%{http_code} " -X POST "$B/api/v1/auth/login" -H "Content-Type: application/json" -d '{}' }  # 400 ×5, then 429
```
Browser: events list, demo login (`demo.user@seatlock.test` / `seatlock-demo`), admin login,
F5 on `/events/<id>`, Google sign-in.

## 3. CI/CD: deploy, verify, roll back

**Normal path (automatic):** push to `main` → **CI** (`.github/workflows/ci.yml`: Maven verify with
Testcontainers + JaCoCo, frontend lint + build) → on success **Deploy** (`deploy.yml`):
1. `image`: builds `backend/` once for `linux/amd64`, tags `sha-<12>` + `latest`, pushes to GHCR (`GITHUB_TOKEN`, `packages: write`), bakes the commit in (`GIT_SHA` → `SEATLOCK_REVISION`)
2. `deploy`: SSH to the VM with the **CI key**, which `authorized_keys` pins to `deploy.sh`:
   `deploy.sh` validates the tag → `docker pull` → sets `APP_IMAGE` → `up -d --no-deps app-1` → waits for *healthy* → same for `app-2` (rolling, W5·D5) → waits for *healthy*
   → on failure prints logs and **rolls back** to the previous image
3. smoke test through Nginx: readiness `UP` **and** `/api/v1/system/version` = the deployed commit

Frontend: Vercel builds every push to `main` by itself.

**Roll back / redeploy a specific version:** Actions → **Deploy** → *Run workflow* → `tag` = `sha-xxxxxxxxxxxx`
(any earlier tag from GHCR). Empty `tag` = build and deploy current `main`. On the VM: `bash infra/scripts/deploy.sh sha-xxxxxxxxxxxx`.

**Which version is live?**
```powershell
curl.exe -s https://seatlock-prajjwal.duckdns.org/api/v1/system/version
```

**Infra changes** (Compose, Nginx, scripts) are not deployed by CI: copy `infra/` as in 1.4, then `dc up -d`
(Nginx config: `dc exec nginx nginx -s reload`).

### CI/CD security setup (done 2026-11-16)

| Piece | Setting |
|---|---|
| NSG | SSH 22 from **Any** (GitHub runners have random IPs); 80/443 Any |
| sshd | key-only (`PasswordAuthentication no`); **fail2ban** `sshd` jail |
| CI key | ed25519, generated on the VM; private half only in GitHub secret `DEPLOY_SSH_KEY`; public half in `~/.ssh/authorized_keys` as `command="/home/ubuntu/seatlock/infra/scripts/deploy.sh",restrict ssh-ed25519 …`, so no shell, no forwarding, only `deploy <tag>` |
| Host key | GitHub secret `DEPLOY_KNOWN_HOSTS` = `seatlock-prajjwal.duckdns.org ssh-ed25519 …` (from `/etc/ssh/ssh_host_ed25519_key.pub`); job uses `StrictHostKeyChecking=yes` |
| Other secrets | `DEPLOY_HOST=seatlock-prajjwal.duckdns.org` |
| GHCR package | **public** (VM pulls without credentials); repo has *Write* under Manage Actions access |

**Rotate the CI key:** generate a new pair on the VM, replace the `seatlock-ci` line in `authorized_keys`,
update `DEPLOY_SSH_KEY`, delete the private key from the VM.

## 3b. Two instances: one-time cutover (W5·D5)

The single `app` service became `app-1` + `app-2` (same image, `SEATLOCK_INSTANCE_ID`), and Nginx balances between them
(upstream `seatlock_app`, see `docs/local-scale.md` for the rules). Infra files reach the VM by `tar` + `scp` (section 1),
so after the W5·D5 PR is merged and its deploy is green:

```powershell
cd D:\Prajjwal\Code\SeatLock
tar -czf $env:TEMP\seatlock-infra.tgz infra
scp -i $HOME\.ssh\azure_seatlock $env:TEMP\seatlock-infra.tgz ubuntu@seatlock-prajjwal.duckdns.org:~/
```
```bash
# Run on the Azure VM (via ssh)
cd ~/seatlock && cp infra/.env.prod ~/env.prod.bak                       # .env.prod is not in the archive; keep a copy anyway
tar -xzf ~/seatlock-infra.tgz -C ~/seatlock && chmod +x infra/scripts/*.sh
dc up -d --remove-orphans          # stops the old "app", starts app-1 + app-2, recreates nginx (~1–2 min of 502s once)
dc ps                              # app-1, app-2 healthy; nginx up
for i in 1 2 3 4; do curl -s https://seatlock-prajjwal.duckdns.org/api/v1/system/version; echo; done   # alternates
free -h && docker stats --no-stream
```
Then run **Actions → Deploy → Run workflow** with the current `sha-…` tag once: the log must show `replacing app-1`,
`app-1 healthy`, `replacing app-2`, `app-2 healthy`, and the smoke test `both instances on sha-…`.

## 4. Change configuration / secrets

```bash
nano infra/.env.prod
dc up -d --force-recreate app-1 app-2   # env is read only at container start (or one at a time: no downtime)
```
- **Frontend URL changed** → update both `FRONTEND_BASE_URL` and `CORS_ALLOWED_ORIGINS` (exact origin, no trailing `/`).
- **Admin password**: changing it in `.env.prod` does *not* update an existing admin (bootstrap only creates a missing one).
- **Rotating JWT keys** logs everyone out (all existing tokens fail verification). That is expected.
- **Payments (Razorpay test mode):** set `RAZORPAY_KEY_ID` (`rzp_test_...`) and `RAZORPAY_KEY_SECRET`, then
  `SEATLOCK_PAYMENTS_GATEWAY=razorpay` and recreate the app. The log must show `Payment gateway: RAZORPAY`. Missing
  or live (`rzp_live_`) keys stop the app at startup on purpose (deploy.sh then rolls back). Open orders created by the
  other gateway can't be paid after a switch; they expire with their booking. Keep `mock` until W4·D3 (verification).
- **Webhook (needed with `razorpay`)**: Razorpay dashboard → Test mode → Accounts & Settings → Webhooks → Add:
  URL `https://seatlock-prajjwal.duckdns.org/api/v1/webhooks/razorpay`, secret = `RAZORPAY_WEBHOOK_SECRET`, events
  `payment.captured`, `order.paid`, `payment.failed`. A local tunnel webhook and the prod one can exist side by side;
  each environment ignores orders it doesn't know (`IGNORED_UNKNOWN_ORDER`).
- **Local webhook testing**: `.\infra\scripts\razorpay-webhook-tunnel.ps1` (backend running) starts a cloudflared
  quick tunnel, prints `<url>/api/v1/webhooks/razorpay` (new URL on every start) and sends one signed synthetic event
  through it (200 = tunnel and secret OK; 400 = the running app has a different `RAZORPAY_WEBHOOK_SECRET`). Add the URL as
  a second webhook with the same secret as local `.env`, test, press Enter to stop, delete the webhook.
- **Rotating the Razorpay key**: generate a new test key, update both values, recreate the app; old orders stay payable
  only with the key that created them, so do it when no booking is waiting for payment.

## 5. Operations

| Task | Command |
|---|---|
| Status | `dc ps` |
| Logs | `dc logs -f app-1 app-2` (JSON/ECS); `dc logs nginx --tail 50` |
| Restart one service | `dc restart app-1` (Nginx sends traffic to app-2 meanwhile) |
| DB shell | `dc exec postgres psql -U seatlock -d seatlock` |
| Backup DB | `dc exec -T postgres pg_dump -U seatlock seatlock \| gzip > ~/backup-$(date +%F).sql.gz` |
| Restore DB | `gunzip -c backup.sql.gz \| dc exec -T postgres psql -U seatlock -d seatlock` |
| Disk / memory | `df -h /`, `free -h`, `docker system df` |
| Clean old images | `docker image prune -f` |
| Certificate status | `dc run --rm --entrypoint certbot certbot certificates` |

Certificates renew automatically (certbot loop every 12 h, renews at < 30 days left; Nginx reloads every 6 h).
Containers use `restart: unless-stopped`, so the stack comes back after a VM reboot.

## 6. Stop / start the VM (save money)

```powershell
az vm deallocate -g seatlock-vm_group -n seatlock-vm   # compute billing stops (~USD 6/month remains)
az vm start      -g seatlock-vm_group -n seatlock-vm   # site back in ~1–2 min
```
Use the portal **Stop** button or `deallocate`. **Never `sudo shutdown`**: the VM stays allocated and keeps billing.
The static IP survives, so DNS and the certificate keep working.

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Frontend: "Cannot reach the SeatLock server" + CORS error in console | Origin not in `CORS_ALLOWED_ORIGINS` | Section 4, exact origin |
| Request URL is `http://localhost:8080` | `VITE_API_BASE_URL` missing at Vercel build time | Set it in Vercel → Redeploy |
| Vercel 404 on refresh | `vercel.json` not deployed | Push it; Vercel redeploys |
| 502 from Nginx | both app instances down or starting | `dc ps`, `dc logs app-1 app-2` |
| SSH times out | your home IP changed | Azure → Networking → SSH rule → *My IP address* |
| certbot `Timeout during connect` | port 80 blocked / DNS wrong | NSG rule for 80 = Any; DuckDNS IP = VM IP |
| Google: `redirect_uri_mismatch` | prod URI not in Google Console | Section 1.6 |
| Deploy job: `Host key verification failed` | `DEPLOY_KNOWN_HOSTS` wrong/stale (VM recreated) | Regenerate the line (section 3) |
| Deploy job: `Permission denied (publickey)` | `DEPLOY_SSH_KEY` incomplete, or `authorized_keys` line missing | Paste the whole key incl. BEGIN/END lines; check the `command=` line |
| Deploy job: `refused: expected 'deploy sha-…'` | bad manual `tag` input | Use `sha-` + 12 hex chars, or empty |
| Deploy job: `manifest unknown` | tag doesn't exist in GHCR | Pick a tag from the package page; site keeps the old version |
| Smoke test: wrong `live revision` | rollback happened (new image unhealthy) | Read the deploy step's log output; fix, push again |
| App won't start: `needs RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET` / `Only Razorpay test-mode keys` | gateway `razorpay` without (test) keys | Section 4: set the `rzp_test_` pair or go back to `mock` |
| Pay now → 503 `PAYMENTS_UNAVAILABLE` | Razorpay rejected the key (401) or timed out | `dc logs app-1 app-2 \| grep Razorpay` shows the HTTP status + error code; check the key pair |
| Log: `Rejected Razorpay webhook: bad or missing signature` + compose warning `The "Xy" variable is not set` | a `$` in `RAZORPAY_WEBHOOK_SECRET` (or any `.env.prod` value) is interpolated by Compose and cut out | Use a hex secret (`openssl rand -hex 32`), update dashboard + `.env.prod`, recreate the app |
| Payments stay "Payment in progress" | verify callback failed and the webhook isn't arriving (wrong URL/secret, events not selected) | Dashboard → Webhooks → the delivery log shows the HTTP status; 400 = secret mismatch |
| Checkout modal doesn't open | `checkout.razorpay.com` blocked (ad blocker, offline) | The page says so; allow the domain |
| CI green locally red in CI | test order/timing differences (e.g. deferred cache `evict`/`clear`) | Use `evictIfPresent`/`invalidate` (immediate) |

## 8. Decommission (planned after ~2–3 months)

1. Final backup (section 5) and copy it off the VM with `scp`. Disable the **Deploy** workflow (Actions → Deploy → ⋯ → Disable).
2. Azure portal → **Resource groups** → `seatlock-vm_group` → **Delete resource group**
   (VM, disk, IP, NIC, NSG, VNet all go; stopping alone still bills disk + IP).
3. DuckDNS: clear the IP. Google Console: remove the prod redirect URI. GitHub: delete the `DEPLOY_*` secrets.
4. Vercel: keep the frontend (shows the UI) or delete the project.
5. Update README: "deployed on Azure (decommissioned on <date> to save cost)".
