package com.seatlock.payment.domain;

/**
 * CREATED: order created at the gateway, waiting for the user to pay.
 * CAPTURED: money received (verified signature / webhook, W4·D3).
 * FAILED: the attempt failed; the user may start a new attempt.
 * REFUNDED: money returned (late payment for an expired booking, cancellation; W4·D4).
 */
public enum PaymentStatus {
    CREATED, CAPTURED, FAILED, REFUNDED;

    /** Open or successful: counts against the one-live-payment-per-booking rule. */
    public boolean isLive() {
        return this == CREATED || this == CAPTURED;
    }
}
