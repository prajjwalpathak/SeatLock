package com.seatlock.payment.gateway;

import java.util.Objects;

/**
 * What we ask the gateway for. {@code receipt} is our own reference (booking public ID + attempt),
 * at most 40 characters (Razorpay's limit); {@code amountPaise} is always computed on the server.
 */
public record CreateOrderCommand(String receipt, long amountPaise, String currency) {

    public static final int MAX_RECEIPT_LENGTH = 40;

    public CreateOrderCommand {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(currency, "currency");
        if (receipt.isBlank() || receipt.length() > MAX_RECEIPT_LENGTH) {
            throw new IllegalArgumentException("receipt must be 1-" + MAX_RECEIPT_LENGTH + " characters");
        }
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be > 0");
        }
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
    }
}
