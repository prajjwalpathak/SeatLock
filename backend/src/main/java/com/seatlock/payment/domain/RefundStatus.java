package com.seatlock.payment.domain;

/** PENDING: waiting for (another) provider call. SUBMITTED: provider accepted it. FAILED: gave up, needs a human. */
public enum RefundStatus {
    PENDING, SUBMITTED, FAILED
}
