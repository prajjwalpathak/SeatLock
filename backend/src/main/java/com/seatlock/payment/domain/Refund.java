package com.seatlock.payment.domain;

import com.seatlock.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One refund owed to a customer (outbox row, V10). Created in the transaction that decided it; sent to the provider
 * later by the refund worker. {@code receipt} is our idempotency key at the provider.
 */
@Entity
@Table(name = "refunds")
public class Refund extends AuditableEntity {

    /** Give up after this many provider failures (then a human looks at last_error). */
    public static final int MAX_ATTEMPTS = 8;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    /** The provider payment to refund: the stored one, or a second payment for the same order. */
    @Column(name = "gateway_payment_id", nullable = false, length = 64, updatable = false)
    private String gatewayPaymentId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RefundStatus status;

    @Column(nullable = false, length = 40, updatable = false)
    private String receipt;

    @Column(name = "gateway_refund_id", length = 64)
    private String gatewayRefundId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Refund() {
        // for JPA
    }

    public Refund(Long paymentId, String gatewayPaymentId, long amountPaise, RefundReason reason, Instant now) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be > 0");
        }
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.gatewayPaymentId = Objects.requireNonNull(gatewayPaymentId, "gatewayPaymentId");
        this.amountPaise = amountPaise;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.status = RefundStatus.PENDING;
        this.receipt = "rf" + UUID.randomUUID().toString().replace("-", ""); // 34 chars, ≤ 40 (Razorpay)
        this.nextAttemptAt = now;
    }

    /** The provider accepted the refund (now or on an earlier attempt we lost the answer to). */
    public void markSubmitted(String gatewayRefundId, Instant now) {
        this.gatewayRefundId = Objects.requireNonNull(gatewayRefundId, "gatewayRefundId");
        this.submittedAt = now;
        this.status = RefundStatus.SUBMITTED;
        this.lastError = null;
    }

    /** A provider call failed: retry later with exponential backoff (30 s, 1 min, 2 min, ... capped at 1 h). */
    public void recordFailure(String error, Instant now) {
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 255));
        if (attempts >= MAX_ATTEMPTS) {
            status = RefundStatus.FAILED;
            return;
        }
        long seconds = Math.min(3600, 30L << Math.min(attempts - 1, 10));
        nextAttemptAt = now.plus(Duration.ofSeconds(seconds));
    }

    public Long getPaymentId() { return paymentId; }
    public String getGatewayPaymentId() { return gatewayPaymentId; }
    public long getAmountPaise() { return amountPaise; }
    public RefundReason getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public String getReceipt() { return receipt; }
    public String getGatewayRefundId() { return gatewayRefundId; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getSubmittedAt() { return submittedAt; }
}
