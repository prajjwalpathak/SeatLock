package com.seatlock.payment.domain;

import com.seatlock.common.exception.ConflictException;
import java.util.Map;

/** 409 {@code INVALID_PAYMENT_TRANSITION}: e.g. capturing a failed payment. Carries status + action. */
public class IllegalPaymentTransitionException extends ConflictException {

    private final PaymentStatus from;
    private final String action;

    IllegalPaymentTransitionException(PaymentStatus from, String action) {
        super("INVALID_PAYMENT_TRANSITION", "A " + from + " payment can't " + action);
        this.from = from;
        this.action = action;
    }

    public PaymentStatus getFrom() { return from; }
    public String getAction() { return action; }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("status", from.name(), "action", action);
    }
}
