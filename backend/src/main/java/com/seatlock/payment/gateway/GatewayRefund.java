package com.seatlock.payment.gateway;

/** A refund as the provider knows it. {@code status}: the provider's word (Razorpay: pending / processed / failed). */
public record GatewayRefund(String refundId, long amountPaise, String status, String receipt) {
}
