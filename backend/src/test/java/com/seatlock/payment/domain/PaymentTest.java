package com.seatlock.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.payment.gateway.GatewayName;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentTest {

    static final Instant NOW = Instant.parse("2026-12-01T10:00:00Z");

    private Payment created() {
        return new Payment(1L, 1, GatewayName.MOCK, "order_mock_A", 50_000, "INR");
    }

    @Test
    void startsCreated() {
        Payment p = created();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(p.getStatus().isLive()).isTrue();
    }

    @Test
    void capturedThenRefunded() {
        Payment p = created();
        p.markCaptured("pay_123", NOW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(p.getGatewayPaymentId()).isEqualTo("pay_123");
        assertThat(p.getCapturedAt()).isEqualTo(NOW);

        p.markRefunded(NOW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.getStatus().isLive()).isFalse();
    }

    @Test
    void failedIsFinalForThisAttempt() {
        Payment p = created();
        p.markFailed("x".repeat(300), NOW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).hasSize(255);

        assertThatThrownBy(() -> p.markCaptured("pay_1", NOW))
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void cannotRefundAnUncapturedPayment() {
        assertThatThrownBy(() -> created().markRefunded(NOW))
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .satisfies(e -> assertThat(((IllegalPaymentTransitionException) e).getCode())
                        .isEqualTo("INVALID_PAYMENT_TRANSITION"));
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThatThrownBy(() -> new Payment(1L, 0, GatewayName.MOCK, "o", 1, "INR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Payment(1L, 1, GatewayName.MOCK, "o", 0, "INR"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
