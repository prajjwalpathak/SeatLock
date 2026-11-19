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

## Update 2026-11-18 (W4·D2): Razorpay adapter on `RestClient`, not `razorpay-java` (user-approved)

The plan's stack named the `razorpay-java` SDK. Its source showed a hard-coded API host (no base URL, so W4·D5
WireMock tests can't redirect it), fixed 60 s read/write timeouts on a shared static OkHttp client, no connect timeout,
and OkHttp 3 + `org.json` as extra dependencies. Decision:

1. `RazorpayGateway` calls the REST API with Spring's **`RestClient`** (spring-web, no new dependency):
   `POST {base-url}/v1/orders`, HTTP Basic `key_id:key_secret`, body `{amount, currency, receipt}`; JDK HttpClient with
   **2 s connect / 5 s read** timeouts (`seatlock.payments.razorpay.*`).
2. Every failure (4xx/5xx, timeout, unreachable, unreadable body) → `PaymentGatewayException` → 503
   `PAYMENTS_UNAVAILABLE`; only Razorpay's error **code** is logged. The response must carry an `order_` id and the
   amount/currency we sent, or it is rejected.
3. **Test keys only:** `gateway=razorpay` without `RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET`, or with a `rzp_live_` key,
   fails startup. `RazorpayProperties.toString()` masks the secret. The test profile pins `gateway: mock`.
4. The order response carries **`keyId`** (public) for Checkout.js, only for orders of the active gateway; the secret
   never leaves the backend. The browser's success callback is a claim until the backend verifies the signature
   (W4·D3, own HMAC-SHA256 with a constant-time compare, replacing the SDK's `Utils`).
5. Contract tests bind `MockRestServiceServer` to the adapter's `RestClient.Builder` (exact URL, auth header, strict
   JSON body, error/timeout handling); WireMock over real HTTP follows on W4·D5.
6. Prod stays on `mock` until W4·D3, so a live demo never takes a test payment it can't confirm.
7. (W4·D5) The adapter's JDK HttpClient is pinned to **HTTP/1.1**: the default HTTP/2 tried an h2c upgrade on plain
   http (WireMock answered with reset streams) and shares one multiplexed connection that a provider-side close
   breaks for the next call. Razorpay's volume here gains nothing from HTTP/2.
