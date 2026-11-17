package com.seatlock.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MockGatewayTest {

    private final MockGateway gateway = new MockGateway();

    @Test
    void createsARazorpayShapedOrderEchoingAmountAndReceipt() {
        GatewayOrder order = gateway.createOrder(new CreateOrderCommand("bk-1-a1", 150_000, "INR"));

        assertThat(order.gateway()).isEqualTo(GatewayName.MOCK);
        assertThat(order.orderId()).matches("order_mock_[A-Za-z0-9]{14}");
        assertThat(order.amountPaise()).isEqualTo(150_000);
        assertThat(order.currency()).isEqualTo("INR");
        assertThat(order.receipt()).isEqualTo("bk-1-a1");
    }

    @Test
    void orderIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(gateway.createOrder(new CreateOrderCommand("r" + i, 100, "INR")).orderId());
        }
        assertThat(ids).hasSize(1_000);
    }

    @Test
    void commandRejectsBadInput() {
        assertThatThrownBy(() -> new CreateOrderCommand("r", 0, "INR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateOrderCommand("x".repeat(41), 100, "INR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateOrderCommand("r", 100, "RUPEE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configSelectsMockAndRefusesRazorpayUntilD2() {
        assertThat(PaymentGatewayConfig.select(GatewayName.MOCK)).isInstanceOf(MockGateway.class);
        assertThatThrownBy(() -> PaymentGatewayConfig.select(GatewayName.RAZORPAY))
                .isInstanceOf(IllegalStateException.class);
    }
}
