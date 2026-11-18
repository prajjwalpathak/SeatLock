# ADR-012: Refunds as an outbox, idempotent by receipt, and a rules-based cancellation policy

**Date:** 2026-11-19 · **Status:** Accepted

## Context

Money must go back in three cases: a payment captured after its booking expired or was cancelled
(`CAPTURED_BOOKING_LOST`, ADR-011), a second payment for an order that was already paid, and a user cancelling a
confirmed booking. A refund is a network call to Razorpay that can time out, fail, or succeed with the answer lost.
It must never be forgotten (the customer loses money) and never be sent twice (we lose money).
Separately, `payment.failed` arrives for every failed try inside Checkout, and Razorpay lets the user retry the same
order, so a failure report must not close the order.

## Options considered

| Option | Verdict |
|---|---|
| **A. Outbox row in the deciding transaction + worker (claim → send → record), idempotent by receipt** | ✅ chosen |
| B. Call the Refund API inside the capture/cancel transaction | Holds locks and a DB connection across the network; a rollback after a successful refund refunds money for a booking we kept |
| C. Call it after commit, fire and forget | A crash or a timeout loses the refund for good |
| D. Rely on Razorpay idempotency headers only | Not something we can verify in test mode; the receipt lookup works with the plain API |

## Decision

1. **`payment.failed`** records the reason on the order; it stays CREATED and payable. A paid order is never changed.
2. **Outbox (V10 `refunds`)**: `RefundService.request()` (`MANDATORY`) writes the row in the transaction that decided
   it: LATE_PAYMENT (full) on `CAPTURED_BOOKING_LOST`, DUPLICATE_PAYMENT (of the second provider payment) on a second
   payment id, CANCELLATION (policy amount) on a paid cancellation. `UNIQUE (gateway_payment_id, reason)` + the payment
   row lock make asking twice return the same row. Receipt = `rf` + 32 hex (≤ 40 chars).
3. **`RefundProcessor`** (every 30 s via `RefundWorker`): **claim** due PENDING rows (`FOR UPDATE SKIP LOCKED`) and
   lease them 2 min; **send** with no transaction (`PaymentGateway.refund`: Razorpay lists the payment's refunds and
   adopts one with our receipt before creating, so a retry after a lost answer never pays twice); **record**:
   SUBMITTED (+ payment REFUNDED unless it's a duplicate's refund) or failure with backoff 30 s · 2ⁿ ≤ 1 h; FAILED after 8
   attempts, logged at ERROR for a human. A refund for a payment of another gateway is never sent.
4. **`RefundPolicyEngine`**: pure Java, tiers from config: **≥ 48 h before the show → 100 %, ≥ 24 h → 50 %, less → not
   cancellable**; the most generous tier still met wins; amounts round down to the paisa; started shows are never
   cancellable. Quote = cancellable, percent, refund, paid, when the percentage drops next, rule text.
5. **Paid cancellation** (`PaidCancellationService`): read outside the transaction, then one transaction: lock payment
   → existing CANCELLATION refund? return it → quote (409 `CANCELLATION_CLOSED`) → `BookingService.refundBooking`
   (booking lock, CONFIRMED → REFUNDED, `show_seats` BOOKED → AVAILABLE) → queue refund. Endpoints
   `GET /bookings/{id}/refund-quote`, `POST /bookings/{id}/refund` (idempotent).

## Consequences

- A refund is visible in the DB the moment it's owed; its state shows exactly how far it got.
- Seats return to sale immediately on cancellation; the money follows asynchronously (UI says 5–7 working days).
- Refund status webhooks (`refund.processed` / `refund.failed`) aren't consumed yet; SUBMITTED is the last state we
  track. Stretch: map them onto the row.
- Two instances can run the worker safely (SKIP LOCKED + lease); a crash mid-call is healed by the lease and the
  receipt lookup.
