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

## 3. Conventions
- Internal keys are `bigint` identity. Bookings expose a random `public_id` (UUID), so IDs in URLs can't be enumerated.
- Enums are `varchar` + `CHECK`, not Postgres `ENUM` types: easier to evolve with Flyway, and mapped with `@Enumerated(EnumType.STRING)`.
- Prices are set per section when an organizer creates a show and copied into `show_seats.price_paise`. A booking stores the price it was charged in `booking_seats.price_paise`, so later price edits never change past bookings.
- `created_at` / `updated_at` are filled by Spring Data auditing using the app's UTC `Clock`.
- FK columns used in joins are indexed in V1. Query indexes for listing/search come on W1·D5, measured with `EXPLAIN ANALYZE` before/after.
- Tables for idempotency keys (W3), payments (W4) and AI audit (W7) arrive in later migrations.