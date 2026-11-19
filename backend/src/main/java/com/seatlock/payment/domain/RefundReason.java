package com.seatlock.payment.domain;

/**
 * LATE_PAYMENT: captured after the booking expired or was cancelled (CAPTURED_BOOKING_LOST).
 * DUPLICATE_PAYMENT: a second payment for an order that was already paid.
 * CANCELLATION: the user cancelled a confirmed booking; amount from the refund policy (may be partial).
 */
public enum RefundReason {
    LATE_PAYMENT, DUPLICATE_PAYMENT, CANCELLATION
}
