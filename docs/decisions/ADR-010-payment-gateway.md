# ADR-010: Payment gateway abstraction, server-side amounts and one live payment per booking

**Date:** 2026-11-18 · **Status:** Accepted

## Context

"Proceed to pay" must create an order at a payment provider (Razorpay from W4·D2). Tests, local runs and k6 load
tests must never call a real provider. The amount must not be something a client can change. A user can double-click,
retry or open two tabs, and a failed payment must be retryable without ever leaving two open orders for one booking.
The booking module owns the booking; payment code must not reach into its repositories (module rule, #16).

## Options considered

| Option | Verdict |
|---|---|
| **A. `PaymentGateway` port, `MockGateway` + Razorpay adapter chosen by `seatlock.payments.gateway`** | ✅ chosen |
| B. Call the Razorpay SDK directly from the service, mock it with Mockito in tests | Tests only; local runs and load tests would still need real keys and network |
| C. Razorpay test mode everywhere (no mock) | Slow, flaky, rate-limited by the provider; CI would need secrets |

Concurrency for "create order":

| Option | Verdict |
|---|---|
| **A. Checks → gateway call outside a transaction → short transaction (row lock, re-check, insert)** | ✅ chosen |
| B. Lock the booking row for the whole call, including the gateway request | A slow provider would hold DB connections and locks (same rule as #79) |
| C. No lock, rely only on the unique index | Works, but every loser hits a constraint violation; the lock serializes the rare same-booking race cleanly |

## Decision

1. **Port + adapters (Adapter/Strategy).** `PaymentGateway { name(); createOrder(CreateOrderCommand) }`. `MockGateway`
   creates Razorpay-shaped IDs (`order_mock_` + 14 characters) in-process. `PaymentGatewayConfig` builds exactly one bean
   from `seatlock.payments.gateway` (`mock` default; `razorpay` arrives on D2 and fails startup until then) and logs
   `Payment gateway: MOCK`. Provider failures → `PaymentGatewayException` → **503 `PAYMENTS_UNAVAILABLE`** + `Retry-After`.
2. **Server-side amount.** `POST /api/v1/bookings/{publicId}/payment-order` has no body. `BookingPricing` sums the prices
   stored on the booking's live seats (fixed from catalog prices at hold time) and requires the sum to equal
   `bookings.total_paise`; a mismatch is refused (500, nothing charged). The gateway's echoed amount must match too.
3. **Payment attempts (V8).** One `payments` row per attempt (`attempt` 1, 2, ...), status `CREATED / CAPTURED / FAILED /
   REFUNDED`; `UNIQUE (gateway, gateway_order_id)` for webhook lookups; partial unique index
   **`ux_payments_live_booking` (booking_id) WHERE status IN ('CREATED','CAPTURED')**: at most one open or successful
   payment per booking. FAILED attempts stay as history and a new attempt may start.
4. **Natural idempotency.** An open order is returned (200) instead of creating another; a new one is 201. No
   Idempotency-Key needed, the same way hold → booking works (#100).
5. **Flow.** `BookingService.checkPayable` (read-only: owner else 404, `HELD`/`PAYMENT_PENDING` else 409
   `INVALID_BOOKING_TRANSITION`, deadline else 409 `BOOKING_EXPIRED`, amount) → open order? return it → gateway call with
   no transaction → `TransactionTemplate`: `BookingService.startPayment` (`Propagation.MANDATORY`, `PESSIMISTIC_WRITE`,
   same checks, HELD → PAYMENT_PENDING) → open order now? return it (ours is left unused at the gateway) → insert.
6. **Module boundary.** Payment uses only `BookingService`'s public methods and the `PayableBooking` record (internal
   booking id for the FK, public id, status, amount, currency, deadline). Dependencies run payment → booking → catalog.
7. **Deadline.** The booking's `expiresAt` is not extended when payment starts. A payment that arrives after it is
   refunded (W4·D4).

## Consequences

- Concurrent losers can leave an unused order at the provider. Unpaid Razorpay orders cost nothing and expire there.
- Every test and load test runs against `MockGateway`; Razorpay is exercised in its own tests (D2) and by hand.
- The receipt sent to the provider is the booking's public id without dashes + `-attempt` (≤ 40 characters).
- Adding a provider means one adapter class and one enum value; nothing in booking or payment flow changes.
