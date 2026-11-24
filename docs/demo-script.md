# SeatLock demo: GIF shot list (W8·D4)

The demo is six short GIFs in `docs/media/` (embedded at the top of the README) instead of one video: each shows a
single feature, loops on GitHub without a click, and is easy to re-record when the UI changes (decision #252).

## Setup used

- **Stack:** local two-instance demo on `http://localhost:8088`
  (`infra\docker-compose.local.yml` + `docker-compose.scale.yml` + `docker-compose.demo.yml`, command in the demo file's header): Nginx serves `frontend\dist`
  and proxies the API, so the browser sees one origin (decision #257).
- **Frontend build:** `frontend\.env.local` with `VITE_API_BASE_URL=http://localhost:8088`, then `npm run build`.
- **Show:** Neon Bazaar, Wed 2 Dec, Monsoon Theatre (more than 48 h away, so the refund is 100 %).
- **Account:** Demo User (one-click demo button). **Theme:** dark. Payments: Razorpay **test mode**
  (mobile 9999999999 → Netbanking → any bank → Success).
- **Frames:** browser screenshots (800×600 desktop, 375×812 phone), assembled with Pillow: one 256-colour palette
  per frame (a shared palette washed out the green/amber seat colours), no dithering, 1.6–3.2 s per frame.

## Shots

| GIF | Size | Frames (in order) | Caption in the README |
|---|---|---|---|
| `01-demo-login.gif` | 800×600 | Events + intro banner → click **Try the demo** → signed-in pill → poster cards | One-click demo login |
| `02-live-seat-map.gif` | 1000×459 | Two halves, YOU / ANOTHER BUYER, same show: both live → you pick F9 + F10 → you hold them, the other screen shows them on hold | Two buyers, one live seat map |
| `03-ai-seat-finder.gif` | 800×600 | Request typed → filters auto-filled + 3 options → suggestions ringed on the map → option 1 selected | AI Seat Finder |
| `04-pay-and-ticket.gif` | 800×600 | Seats held (timer 7:59) → booking summary with **Pay ₹750** → Razorpay test checkout → **Confirmed** → QR ticket | Hold → pay → ticket |
| `05-refund-assistant.gif` | 676×586 | Ticket + cancel card → "How much will I get back?" answer (100 %, ₹750) → confirm cancel → **Cancelled · refunded** | Refund Assistant → cancel |
| `06-phone.gif` | 375×812 | Phone header + banner + chips → poster cards → seat map (finder collapsed, swipe hint) → E9 + E10 selected | Phone layout |

## Re-recording a clip

1. Start the demo stack and build the frontend as above; sign in with the demo button; pick a show > 48 h away.
2. Capture 3–5 still frames per clip (one per step in the table), same window size within a clip.
3. Assemble: per-frame `quantize(256, FASTOCTREE, dither NONE)`, crop all frames to the smallest height, save with
   `loop=0` and per-frame durations. Keep each GIF under ~500 KB so the README loads fast.
4. Replace the file in `docs/media/` with the same name (the README links don't change).
