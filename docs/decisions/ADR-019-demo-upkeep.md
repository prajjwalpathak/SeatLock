# ADR-019: Nightly demo upkeep (show top-up + demo-account reset)

**Date:** 2026-11-23 · **Status:** Accepted (W8·D1)

## Context

The live demo has no real users. Two things decay on their own:

1. **Shows run out.** `V2__seed.sql` dated the 90 seed shows relative to the migration date (3 per event, a week
   apart). On prod (migrated mid-November) the first events lose their last show in early December and the catalog
   is empty by early January, which is exactly when the project is shown to interviewers.
2. **The shared demo account fills up.** Everyone who clicks "Try the demo" signs in as `demo.user@seatlock.test`.
   Its confirmed bookings keep seats BOOKED and fill "My bookings" with strangers' tickets.

## Decision

Two jobs, one per module, both `@Scheduled` at **03:30 IST**, kill switch `SEATLOCK_DEMO_RESET_ENABLED`:

- **`catalog.DemoShowTopUpJob`:** every PUBLISHED event with fewer than 3 upcoming shows gets new ones one week after
  its latest show (same venue, time of day and per-seat prices). `INSERT … ON CONFLICT DO NOTHING` skips taken slots
  (unique start, venue no-overlap exclusion constraint) and the next day is tried. One transaction under
  `pg_try_advisory_xact_lock`: on 2 instances one runs, the other skips. Caches evicted after commit via
  `ShowScheduledEvent`.
- **`payment.DemoBookingResetJob`:** for the demo account only, CONFIRMED bookings confirmed ≥ 12 h ago are cancelled
  **only if the policy gives a full refund now**, through `PaidCancellationService.cancel` (same locks, seat release
  and refund outbox as a user's Cancel). HELD / PAYMENT_PENDING bookings are never selected.

## Rejected

- **Truncate and re-seed nightly:** deletes payment and refund history, breaks the FKs, and would wipe a booking being
  paid at 03:30.
- **Raw SQL to free seats:** bypasses the state machine, live seat events and the refund outbox.
- **Partial refunds from a job:** money decisions stay with the user; bookings near their show are left to pass.
- **A distributed lock for the booking reset:** not needed, a cancellation is idempotent and serialized on the
  payment row.

## Consequences

- The catalog stays bookable indefinitely without manual seeding; organizer-created shows count too.
- Demo bookings disappear from "My bookings" overnight (refunded, test money only).
- Tests: `DemoShowTopUpIntegrationTest` (spacing, prices, conflicts, drafts, idempotent), `DemoBookingResetIntegrationTest`
  (old vs recent vs in-flight), `DemoBookingResetJobTest` (full refund only, failures isolated, disabled switch).
