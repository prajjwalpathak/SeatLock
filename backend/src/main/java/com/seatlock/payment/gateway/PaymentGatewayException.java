package com.seatlock.payment.gateway;

/** The provider failed or refused (network, 5xx, bad credentials). Mapped to 503 by the payment service (task B). */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    public PaymentGatewayException(String message) {
        super(message);
    }
}
