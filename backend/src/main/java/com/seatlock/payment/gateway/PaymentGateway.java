package com.seatlock.payment.gateway;

/**
 * Port to a payment provider (Adapter/Strategy). Booking and payment code depend only on this interface;
 * {@code seatlock.payments.gateway} picks the implementation: {@link MockGateway} for local runs, tests and
 * load tests (never calls a real provider), Razorpay from W4·D2.
 */
public interface PaymentGateway {

    GatewayName name();

    /**
     * Creates an order at the provider. Throws {@link PaymentGatewayException} if the provider can't be reached
     * or rejects the request; nothing is stored on our side in that case.
     */
    GatewayOrder createOrder(CreateOrderCommand command);

    /**
     * Refunds (part of) a captured payment. <b>Idempotent by receipt</b>: if a refund with this receipt already exists
     * at the provider (an earlier call succeeded but its answer was lost), that refund is returned instead of
     * creating a second one. Throws {@link PaymentGatewayException} when the provider can't be reached or refuses.
     */
    GatewayRefund refund(RefundCommand command);

    /**
     * Public key the browser needs to open this provider's checkout (Razorpay: the key ID), or null when there is
     * no browser checkout (the mock). Never a secret.
     */
    default String checkoutKeyId() {
        return null;
    }
}
