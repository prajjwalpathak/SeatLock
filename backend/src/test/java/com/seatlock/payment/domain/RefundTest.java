package com.seatlock.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundTest {

    static final Instant NOW = Instant.parse("2026-12-04T10:00:00Z");

    private static Refund refund() {
        return new Refund(1L, "pay_1", 5000, RefundReason.LATE_PAYMENT, NOW);
    }

    @Test
    void startsPendingAndDueNowWithAProviderSizedReceipt() {
        Refund r = refund();
        assertThat(r.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(r.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(r.getReceipt()).startsWith("rf").hasSizeLessThanOrEqualTo(40);
        assertThat(refund().getReceipt()).isNotEqualTo(r.getReceipt());
    }

    @Test
    void failuresBackOffExponentiallyThenGiveUp() {
        Refund r = refund();
        r.recordFailure("timeout", NOW);
        assertThat(r.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        r.recordFailure("timeout", NOW);
        assertThat(r.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        r.recordFailure("timeout", NOW);
        assertThat(r.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(120));

        for (int i = r.getAttempts(); i < Refund.MAX_ATTEMPTS; i++) {
            r.recordFailure("still down", NOW);
        }
        assertThat(r.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(r.getLastError()).isEqualTo("still down");
    }

    @Test
    void submittedKeepsTheProviderId() {
        Refund r = refund();
        r.recordFailure("timeout", NOW);
        r.markSubmitted("rfnd_1", NOW);
        assertThat(r.getStatus()).isEqualTo(RefundStatus.SUBMITTED);
        assertThat(r.getGatewayRefundId()).isEqualTo("rfnd_1");
        assertThat(r.getLastError()).isNull();
    }
}
