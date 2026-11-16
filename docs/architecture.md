# SeatLock: Architecture

## 1. Data model

```mermaid
erDiagram
    USERS ||--o{ EVENTS : organizes
    USERS ||--o{ BOOKINGS : makes
    VENUES ||--|{ SEATS : contains
    VENUES ||--o{ SHOWS : hosts
    EVENTS ||--o{ SHOWS : "scheduled as"
    SHOWS ||--|{ SHOW_SEATS : "sells"
    SEATS ||--o{ SHOW_SEATS : "appears in"
    SHOWS ||--o{ BOOKINGS : "booked for"
    BOOKINGS ||--|{ BOOKING_SEATS : includes
    SHOW_SEATS ||--o{ BOOKING_SEATS : "reserved by"

    USERS {
        bigint id PK
        varchar email UK "stored lower-case"
        varchar password_hash "null for Google-only users"
        varchar display_name
        varchar role "USER | ORGANIZER | ADMIN"
        varchar auth_provider "LOCAL | GOOGLE"
        timestamptz created_at
        timestamptz updated_at
    }
    VENUES {
        bigint id PK
        varchar name
        varchar city
        varchar address
        timestamptz created_at
        timestamptz updated_at
    }
    SEATS {
        bigint id PK
        bigint venue_id FK
        varchar section "e.g. PLATINUM, GOLD"
        varchar row_label "A, B, ..."
        int seat_number
        int grid_row "for rendering the map"
        int grid_col
        boolean is_aisle
    }
    EVENTS {
        bigint id PK
        bigint organizer_id FK
        varchar title
        text description
        varchar category
        varchar language
        int duration_minutes
        varchar status "DRAFT | PUBLISHED | CANCELLED"
        timestamptz created_at
        timestamptz updated_at
    }
    SHOWS {
        bigint id PK
        bigint event_id FK
        bigint venue_id FK
        timestamptz starts_at
        varchar status "SCHEDULED | CANCELLED"
        timestamptz created_at
        timestamptz updated_at
    }
    SHOW_SEATS {
        bigint id PK
        bigint show_id FK
        bigint seat_id FK
        bigint price_paise
        varchar status "AVAILABLE | HELD | BOOKED"
        bigint version "optimistic lock"
    }
    BOOKINGS {
        bigint id PK
        uuid public_id UK "exposed in APIs"
        bigint user_id FK
        bigint show_id FK
        uuid hold_id UK "one booking per hold"
        varchar status "HELD | PAYMENT_PENDING | CONFIRMED | EXPIRED | CANCELLED | REFUNDED"
        bigint total_paise
        char currency "INR"
        timestamptz expires_at
        timestamptz confirmed_at
        timestamptz cancelled_at
        bigint version "optimistic lock"
        timestamptz created_at
        timestamptz updated_at
    }
    BOOKING_SEATS {
        bigint id PK
        bigint booking_id FK
        bigint show_id FK
        bigint seat_id FK
        bigint price_paise
        boolean active "false once EXPIRED/CANCELLED/REFUNDED"
    }
```

## 2. Correctness rules enforced by the schema

| Rule | How |
|---|---|
| One row per seat per show | `UNIQUE (show_id, seat_id)` on `show_seats` |
| **A seat can never be in two live bookings** | **Partial unique index** on `booking_seats (show_id, seat_id) WHERE active`. This is the last line of defense, even if Redis fails. Released seats (`active = false`) can be booked again, and history is kept. |
| A booked seat belongs to that show | Composite FK `booking_seats (show_id, seat_id)` → `show_seats (show_id, seat_id)` |
| A hold becomes at most one booking | `UNIQUE (hold_id)` on `bookings`, which makes confirm idempotent |
| Money is never fractional or negative | `bigint` paise + `CHECK (... >= 0)` / `> 0` |
| Time is unambiguous | `timestamptz` everywhere, UTC in the app (`Instant`) |
| Valid states only | Enums stored as `varchar` + `CHECK (status IN (...))` |
| Concurrent updates detected | `version` columns: `show_seats` (Optimistic strategy), `bookings` (state transitions racing the expiry sweeper / webhooks) |

## 2a. Authentication (W2·D1, see ADR-002)

```mermaid
sequenceDiagram
    participant SPA
    participant API
    participant DB
    SPA->>API: POST /api/v1/auth/login {email, password}
    API->>DB: find user by e-mail
    API->>API: BCrypt check, sign RS256 JWT (8 h)
    API-->>SPA: {accessToken, tokenType: Bearer, expiresIn: 28800}
    SPA->>API: GET /api/v1/me  Authorization: Bearer <jwt>
    API->>API: verify signature + exp/iss/aud (no DB)
    API->>DB: load user by sub (only /me needs it)
    API-->>SPA: {id, email, displayName, role}
```

Public: `GET /api/v1/{events,shows,system}/**`, `POST /api/v1/auth/{register,login}`, health,
Swagger. Everything else requires a valid token (deny by default).

| Route | Who | Gates |
|---|---|---|
| `GET /api/v1/me` | any signed-in user | URL: authenticated |
| `GET /api/v1/organizer/events` | ORGANIZER, ADMIN | URL prefix + `@PreAuthorize`; data scoped to token `sub` |
| `GET /api/v1/admin/users` | ADMIN | URL prefix + `@PreAuthorize` |

Role hierarchy: ADMIN ⊃ ORGANIZER ⊃ USER. Demo logins and admin bootstrap: ADR-002 §8.

**Google sign-in (W2·D3, ADR-003):** a separate filter chain for `/oauth2/**` and
`/login/oauth2/**` runs the OAuth2 authorization-code flow (PKCE, state in Redis + a binding
cookie, no session). It ends with a 60-second one-time code that the SPA exchanges at
`POST /api/v1/auth/oauth/exchange` for our own JWT; Google's tokens are never used after sign-in.

**Frontend auth:** the token is kept in localStorage (no refresh token, decision #43).
`AuthProvider` loads `/me`, signs out at expiry, on `INVALID_TOKEN` and across tabs; all user data
in the query cache sits under the `['me', …]` key and is dropped on sign-out. `RequireAuth`
guards `/account` and `/organizer/events` (UX only; the API enforces).

## 2b. Caching and venue scheduling (W2·D4)

- **Redis cache (ADR-004):** event detail (5 min) and event list pages (60 s) are cached,
  because benchmarks showed p95 −32% and −21% against the uncached code. The seat map is **not**
  cached: its single indexed query was faster than every cached variant, and it carries live seat
  status. Cache hits never borrow a DB connection; evictions run after commit (`ShowScheduledEvent`).
- **No overlapping shows (CORRECTNESS.md §1b):** `shows.ends_at` = start + duration + 30 min, and
  the exclusion constraint `ex_shows_venue_no_overlap` makes two scheduled shows at one venue at
  the same time impossible. Organizers create shows with `POST /api/v1/organizer/events/{id}/shows`
  (one price per section; ownership from the token).

## 2c. Live seat map (W3·D1)

`GET /api/v1/shows/{id}/seats` merges two sources into one response:

| Source | Holds | Lifetime | Wins? |
|---|---|---|---|
| Postgres `show_seats.status` | **BOOKED** (and the layout, prices) | durable | always |
| Redis `hold:show:{showId}:seat:{seatId}` → `holdId` | **HELD** | key TTL (the TTL *is* the hold) | only turns AVAILABLE into HELD |

```mermaid
sequenceDiagram
    autonumber
    participant UI as React SeatMap
    participant API as ShowCatalogService (catalog)
    participant DB as Postgres
    participant R as Redis
    UI->>API: GET /api/v1/shows/34/seats
    API->>DB: bookable show? (header query)
    API->>DB: all seats + price + status (one indexed join)
    API->>R: MGET hold keys of every AVAILABLE seat (one command, one round trip)
    alt Redis reachable
        R-->>API: holdId or nil per key
        API-->>UI: 200, seats with AVAILABLE / HELD / BOOKED, holdsLive = true
    else Redis down (REJECT_COMMANDS → immediate DataAccessException)
        API-->>UI: 200, DB statuses only, holdsLive = false (UI shows a banner)
    end
```

- **Module direction:** catalog defines the port `SeatHoldLookup`; booking implements it
  (`booking.hold.SeatHoldReader`, key schema in `HoldKeys`). Dependencies run booking → catalog only.
- **No transaction around the request:** each query borrows a connection for itself, so no DB
  connection is held while waiting on Redis (a hung Redis can't drain the Hikari pool).
- **Never cached** (ADR-004) and sent with `Cache-Control: no-store`. Measured cost of the overlay:
  none beyond noise (`benchmarks.md` §2).
- **Fail fast:** Lettuce `REJECT_COMMANDS` while disconnected; Redis down costs ~5 ms instead of the
  500 ms command timeout (`FAILURES.md` F1). Being shown as AVAILABLE while Redis is down is safe:
  the hold script (W3·D2) and the DB oversell guard still reject a taken seat.
- **UI:** `/shows/:showId` renders rows as CSS grids by `gridCol` (aisle = skipped column), section
  headings with prices, native `<button>` seats with `aria-pressed` and a symbol per state. Selection
  is client-only until holds exist (D2 API, D5 UI) and is derived from the latest map, so a seat taken
  by someone else drops out. Refetch on focus + Refresh button until WebSocket push (W5).

## 2d. Seat holds (W3·D2, ADR-006)

| Endpoint | Who | Result |
|---|---|---|
| `POST /api/v1/shows/{showId}/holds` `{"seatIds":[…]}` | signed in | 201 `{holdId, showId, seats, totalPaise, expiresAt}` · 409 `SEATS_UNAVAILABLE` (+ `seatIds`) / `HOLD_EXISTS` / `SHOW_STARTED` · 400 `TOO_MANY_SEATS` / `DUPLICATE_SEATS` / `UNKNOWN_SEATS` / `NO_SEATS` · 503 `HOLDS_UNAVAILABLE` |
| `GET /api/v1/holds/{holdId}` | owner | 200, else 404 |
| `DELETE /api/v1/holds/{holdId}` | owner | 204, else 404 |

```mermaid
sequenceDiagram
    autonumber
    participant UI as React (D5)
    participant C as HoldController
    participant S as HoldService (booking)
    participant CAT as ShowCatalogService (catalog)
    participant DB as Postgres
    participant R as Redis (hold_seats.lua)
    UI->>C: POST /shows/34/holds {seatIds} + Bearer JWT
    C->>S: createHold(userId from token, showId, seatIds)
    S->>S: 1–6 seats, no duplicates
    S->>CAT: findHoldableSeats(showId, seatIds)
    CAT->>DB: bookable show? + those seats (price, status)
    S->>S: not started, seats in show, none BOOKED
    S->>R: EVALSHA hold_seats.lua (seat keys, user key, record key)
    alt all free
        R-->>S: OK (all keys set, TTL 8 min)
        S-->>UI: 201 hold (server prices, expiresAt)
    else some seat held
        R-->>S: SEATS_TAKEN [seat positions] (nothing written)
        S-->>UI: 409 SEATS_UNAVAILABLE + seatIds
    else user already holds
        R-->>S: HOLD_EXISTS
        S-->>UI: 409 HOLD_EXISTS
    end
```

- The seat map (§2c) reads the same seat keys, so a new hold shows as HELD on the next fetch.
- No DB transaction around the request; booking only calls catalog's public service (booking → catalog).

## 3. Conventions
- Internal keys are `bigint` identity. Bookings expose a random `public_id` (UUID), so IDs in URLs can't be enumerated.
- Enums are `varchar` + `CHECK`, not Postgres `ENUM` types: easier to evolve with Flyway, and mapped with `@Enumerated(EnumType.STRING)`.
- Prices are set per section when an organizer creates a show and copied into `show_seats.price_paise`. A booking stores the price it was charged in `booking_seats.price_paise`, so later price edits never change past bookings.
- `created_at` / `updated_at` are filled by Spring Data auditing using the app's UTC `Clock`.
- FK columns used in joins are indexed in V1. Query indexes for listing/search come on W1·D5, measured with `EXPLAIN ANALYZE` before/after.
- Tables for idempotency keys (W3), payments (W4) and AI audit (W7) arrive in later migrations.