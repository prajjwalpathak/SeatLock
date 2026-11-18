package com.seatlock.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What Razorpay Checkout handed the browser (razorpay_order_id, razorpay_payment_id, razorpay_signature). */
public record VerifyPaymentRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 64) String paymentId,
        @NotBlank @Size(max = 128) String signature) {
}
