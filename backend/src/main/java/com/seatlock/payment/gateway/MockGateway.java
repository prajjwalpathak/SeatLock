package com.seatlock.payment.gateway;

import java.security.SecureRandom;

/**
 * In-process fake gateway: creates orders with Razorpay-shaped IDs ({@code order_mock_} + 14 characters)
 * without any network call. Used locally, in tests and in k6 load tests. Thread-safe.
 */
public class MockGateway implements PaymentGateway {

    static final String ORDER_PREFIX = "order_mock_";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int ID_LENGTH = 14;

    private final SecureRandom random = new SecureRandom();

    @Override
    public GatewayName name() {
        return GatewayName.MOCK;
    }

    @Override
    public GatewayOrder createOrder(CreateOrderCommand command) {
        return new GatewayOrder(GatewayName.MOCK, newOrderId(), command.amountPaise(), command.currency(),
                command.receipt());
    }

    private String newOrderId() {
        char[] id = new char[ID_LENGTH];
        for (int i = 0; i < ID_LENGTH; i++) {
            id[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return ORDER_PREFIX + new String(id);
    }
}
