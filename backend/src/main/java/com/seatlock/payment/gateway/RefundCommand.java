package com.seatlock.payment.gateway;

import java.util.Objects;

/** Refund {@code amountPaise} of provider payment {@code gatewayPaymentId}. {@code receipt} = our idempotency key. */
public record RefundCommand(String gatewayPaymentId, long amountPaise, String receipt) {

    public RefundCommand {
        Objects.requireNonNull(gatewayPaymentId, "gatewayPaymentId");
        Objects.requireNonNull(receipt, "receipt");
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be > 0");
        }
    }
}
