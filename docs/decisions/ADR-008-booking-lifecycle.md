# ADR-008: Booking lifecycle: State pattern, hold → booking in Postgres, SKIP LOCKED expiry

**Date:** 2026-11-17 · **Status:** Accepted

## Context

A Redis hold (ADR-006) reserves seats for 8 minutes but isn't durable. Before money moves (W4), the claim has to
live in Postgres, where the partial unique index `ux_booking_seats_live_seat` makes an oversell impossible.
A booking then moves through payment states, can be cancelled, and must expire on its own if nobody pays.
Several actors touch the same booking: the user, the expiry sweeper, and (W4) the payment webhook, on two instances.

## Decisions

1. **State pattern** (`BookingState`, sealed: `Held`, `PaymentPending`, `Confirmed`, `Terminal`). Each state overrides
   only its allowed moves; everything else throws `IllegalBookingTransitionException` (409, with status + action).
   `Booking` keeps the enum column and applies side effects (timestamps, releasing seats).
   `HELD → PAYMENT_PENDING → CONFIRMED → REFUNDED`; `HELD/PAYMENT_PENDING → EXPIRED/CANCELLED`. No `HELD → CONFIRMED`,
   no expiring or cancelling a paid booking, nothing leaves a terminal state. Expiry is refused before `expiresAt`.
2. **Conversion at "Proceed to pay"** (`POST /api/v1/holds/{holdId}/booking`, user-approved): the booking keeps the
   hold's `expiresAt` (no extension). One transaction: `show_seats` AVAILABLE → HELD as a compare-and-set (all or
   nothing), then booking + `booking_seats` with `saveAndFlush` so the partial unique index fires inside it. After
   commit the Redis hold is released; the seat map keeps showing HELD from the DB.
3. **Naturally idempotent conversion:** `hold_id` is UNIQUE, so a repeat returns the same booking (200, not 201) and
   needs no Idempotency-Key. Every way of losing a race to a concurrent conversion of the same hold (hold already
   released, seats already HELD, `uq_bookings_hold_id`) re-checks for the winner's booking and returns it.
4. **Module boundary:** booking changes seat statuses only through catalog's `ShowSeatStatusService`
   (`markHeld` / `markAvailable`, conditional UPDATEs that join the caller's transaction).
5. **Expiry sweeper** every 30 s: per batch (100, max 20 per run) one transaction with
   `SELECT … FOR UPDATE SKIP LOCKED`, `booking.expire(now)` via the state machine, `markAvailable`. Parallel sweepers
   split the rows; rows a user action or payment holds are skipped and retried next run. `@Version` stays as a
   second guard.
6. **User actions lock the row** (`PESSIMISTIC_WRITE`, e.g. cancel): they wait for a sweeper's commit and then see
   the final state (409 if it expired). Cancelling a cancelled booking returns it again (retry-safe).
7. **No public confirm** before payments (user-approved): `confirm()` exists and is tested; the W4 webhook calls it.
8. Seats are released by **deactivating** `booking_seats` rows (`active = false`), never deleting: the index frees the
   seat for resale and history is kept.

## Consequences

- Postgres is the source of truth from conversion on; Redis only handles the short, contended selection phase.
- Between the sweeper's interval (≤ 30 s) and a booking's `expiresAt`, an expired booking can still look HELD for
  a few seconds. Harmless: nobody can pay it (W4 checks the state), and the seat frees on the next sweep.
- A partial index for the sweeper query (`expires_at WHERE status IN (...)`) is deferred to W6·D5 (indexes only
  from measured plans, #29).
- Tested: `BookingLifecycleTest` (30-row transition table), `BookingConversionIntegrationTest` (incl. DB index
  catching what Redis missed, 5 concurrent conversions → 1 booking), `BookingExpiryIntegrationTest` (two sweepers →
  each booking once; locked row skipped, not waited on), `BookingApiIntegrationTest`, security matrix rows.
