package com.seatlock.payment.gateway;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process fake gateway: creates orders with Razorpay-shaped IDs ({@code order_mock_} + 14 characters)
 * without any network call. Used locally, in tests and in k6 load tests. Thread-safe.
 */
public class MockGateway implements PaymentGateway {

    static final String ORDER_PREFIX = "order_mock_";
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int ID_LENGTH = 14;

    private final SecureRandom random = new SecureRandom();
    /** receipt → refund, so a repeated refund call returns the same refund (like Razorpay with our receipt lookup). */
    private final Map<String, GatewayRefund> refunds = new ConcurrentHashMap<>();

    @Override
    public GatewayName name() {
        return GatewayName.MOCK;
    }

    @Override
    public GatewayOrder createOrder(CreateOrderCommand command) {
        return new GatewayOrder(GatewayName.MOCK, newOrderId(), command.amountPaise(), command.currency(),
                command.receipt());
    }

    @Override
    public GatewayRefund refund(RefundCommand command) {
        return refunds.computeIfAbsent(command.receipt(), r ->
                new GatewayRefund("rfnd_mock_" + randomId(), command.amountPaise(), "processed", r));
    }

    private String newOrderId() {
        char[] id = new char[ID_LENGTH];
        for (int i = 0; i < ID_LENGTH; i++) {
            id[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return ORDER_PREFIX + new String(id);
    }

    private String randomId() {
        return newOrderId().substring(ORDER_PREFIX.length());
    }
}
