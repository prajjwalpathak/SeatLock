package com.seatlock.payment.gateway;

/** Which provider handled a payment; stored on every payment row. Selected by {@code seatlock.payments.gateway}. */
public enum GatewayName {
    MOCK, RAZORPAY
}
