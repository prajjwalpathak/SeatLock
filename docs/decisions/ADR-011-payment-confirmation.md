# ADR-011: Confirming payments: signed callback + deduplicated webhook, one transaction, fixed lock order

**Date:** 2026-11-18 · **Status:** Accepted

## Context

A Razorpay payment can reach us twice and in any order: the browser forwards Checkout's success payload (and an
impatient user may click again), and Razorpay sends webhooks (`payment.captured`, `order.paid`) at least once, with
redeliveries. The browser may never call back at all (tab closed, network gone). Meanwhile the expiry sweeper may be
expiring the same booking. Nothing that arrives may be trusted without a signature, and a booking must never end up
CONFIRMED without BOOKED seats, or EXPIRED with money taken and no record of it.

## Options considered

| Option | Verdict |
|---|---|
| **A. Callback and webhook both call one `PaymentCaptureService.capture()`; payment row locked first, then the booking** | ✅ chosen |
| B. Webhook only (ignore the callback) | Slower UX (webhook latency) and no confirmation at all while the tunnel/URL is down |
| C. Callback only | A closed tab leaves a paid booking unconfirmed, then expired |
| D. Optimistic `@Version` retries instead of row locks | Works, but every loser needs retry logic; the rows are contended only by a few requests for one payment |

## Decision

1. **Signatures** (`RazorpaySignatures`, own code, no SDK): checkout `HMAC_SHA256(key_secret, order_id|payment_id)`;
   webhook `HMAC_SHA256(webhook_secret, raw body)` checked on the exact bytes before parsing; lowercase hex,
   constant-time compare; a blank secret never validates. `gateway=razorpay` requires both secrets at startup.
2. **One capture path, one transaction.** `capture(gateway, orderId, paymentId, amount)`: lock the payment row
   (`findForUpdateByGatewayOrder`), check the reported amount, CREATED → CAPTURED, then `BookingService.confirmPayment`
   (`MANDATORY`): lock the booking row, PAYMENT_PENDING → CONFIRMED, `show_seats` HELD → BOOKED (compare-and-set, all
   seats or rollback).
3. **Outcomes, not exceptions:** `CONFIRMED`; `ALREADY_DONE` (same payment id again); `CAPTURED_BOOKING_LOST` (the
   sweeper or a cancel won: payment stays CAPTURED for the refund, W4·D4); `NEEDS_REVIEW` (amount mismatch, a second
   payment id on the order, or a capture on a FAILED attempt). A late payment that beats the sweeper still confirms:
   the seats were never released.
4. **Lock order** payment → booking. Order creation locks the booking and inserts a new payment row; the sweeper
   (SKIP LOCKED) and cancel lock only the booking. No cycle, so no deadlock.
5. **Checkout callback** `POST /api/v1/payments/razorpay/verify` (USER): signature, then the caller must own the
   order's booking (else 404, like an unknown order), then capture; answers `{outcome, booking}` with 200.
6. **Webhook** `POST /api/v1/webhooks/razorpay` (public, HMAC-authenticated): raw `byte[]` body; event id from
   `X-Razorpay-Event-Id` (else SHA-256 of the body) claimed in `webhook_events` with `INSERT … ON CONFLICT DO NOTHING`
   **in the same transaction** as the capture. Duplicate → 200 skipped; bad signature → 400; unknown order or other
   event → 200 recorded as ignored; processing error → 500 and the claim rolls back, so the retry is processed.
   `payment.failed` is recorded and handled on W4·D4.

## Consequences

- Proven by `PaymentConfirmRaceIntegrationTest`: 10 callbacks + 10 webhooks at once → exactly one CONFIRMED, the rest
  ALREADY_DONE; with the sweeper running too → either CONFIRMED + BOOKED or EXPIRED + AVAILABLE + CAPTURED_BOOKING_LOST,
  never anything in between; the payment is CAPTURED exactly once either way.
- `webhook_events` grows by one row per delivery; a cleanup job can come later (W8), the table is tiny.
- The frontend treats Checkout's success as a claim: it calls verify, and if that fails the webhook still confirms
  while the page polls.
- Local webhook testing needs a public URL (cloudflared quick tunnel); its URL changes on each start.
