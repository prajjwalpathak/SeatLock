package com.seatlock.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatlock.payment.domain.Payment;
import com.seatlock.payment.domain.PaymentRepository;
import com.seatlock.payment.domain.Refund;
import com.seatlock.payment.domain.RefundReason;
import com.seatlock.payment.domain.RefundRepository;
import com.seatlock.payment.domain.RefundStatus;
import com.seatlock.payment.gateway.GatewayName;
import com.seatlock.payment.gateway.PaymentGateway;
import com.seatlock.payment.gateway.PaymentGatewayException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Failure handling without a database: a provider error must leave a retryable PENDING refund, never a lost one. */
class RefundProcessorTest {

    static final Instant NOW = Instant.parse("2026-12-04T10:00:00Z");

    RefundRepository refunds = mock(RefundRepository.class);
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentGateway gateway = mock(PaymentGateway.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    RefundProcessor processor = new RefundProcessor(refunds, payments, gateway, tx,
            new RefundProperties(20, Duration.ofMinutes(2), List.of(new RefundProperties.Tier(0, 100))),
            Clock.fixed(NOW, ZoneOffset.UTC));

    Refund refund = new Refund(1L, "pay_1", 5000, RefundReason.LATE_PAYMENT, NOW);
    Payment payment = new Payment(7L, 1, GatewayName.MOCK, "order_mock_1", 5000, "INR");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        when(refunds.findById(1L)).thenReturn(Optional.of(refund));
        when(payments.findById(1L)).thenReturn(Optional.of(payment));
        when(gateway.name()).thenReturn(GatewayName.MOCK);
    }

    @Test
    void aProviderErrorSchedulesARetry() {
        when(gateway.refund(any())).thenThrow(new PaymentGatewayException("Razorpay unreachable"));

        assertThat(processor.send(1L)).isFalse();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.getAttempts()).isEqualTo(1);
        assertThat(refund.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(refund.getLastError()).contains("unreachable");
    }

    @Test
    void aRefundForAnotherGatewaysPaymentIsNotSent() {
        when(gateway.name()).thenReturn(GatewayName.RAZORPAY);

        assertThat(processor.send(1L)).isFalse();

        verify(gateway, never()).refund(any());
        assertThat(refund.getLastError()).contains("MOCK");
    }
}
