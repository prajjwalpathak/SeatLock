package com.seatlock.payment.domain;

import com.seatlock.common.persistence.AuditableEntity;
import com.seatlock.payment.gateway.GatewayName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One payment attempt for a booking. The booking is referenced by ID only (module rule, decision #16).
 * The amount is fixed when the gateway order is created and never changes afterwards.
 */
@Entity
@Table(name = "payments")
public class Payment extends AuditableEntity {

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    /** 1 for the first attempt, +1 for each retry after a failure. */
    @Column(nullable = false, updatable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private GatewayName gateway;

    @Column(name = "gateway_order_id", nullable = false, length = 64, updatable = false)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 64)
    private String gatewayPaymentId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    /** Webhook vs. checkout callback vs. refund job racing on the same row (W4·D3–D4). */
    @Version
    @Column(nullable = false)
    private long version;

    protected Payment() {
        // for JPA
    }

    public Payment(Long bookingId, int attempt, GatewayName gateway, String gatewayOrderId,
                   long amountPaise, String currency) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be > 0");
        }
        this.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        this.attempt = attempt;
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.gatewayOrderId = Objects.requireNonNull(gatewayOrderId, "gatewayOrderId");
        this.amountPaise = amountPaise;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.status = PaymentStatus.CREATED;
    }

    // ------------------------------------------------------------------ lifecycle

    /** CREATED → CAPTURED: the gateway confirmed the money (W4·D3). */
    public void markCaptured(String gatewayPaymentId, Instant now) {
        require(PaymentStatus.CREATED, "be captured");
        this.gatewayPaymentId = Objects.requireNonNull(gatewayPaymentId, "gatewayPaymentId");
        this.capturedAt = now;
        this.status = PaymentStatus.CAPTURED;
    }

    /** CREATED → FAILED: frees the booking for a new attempt. */
    public void markFailed(String reason, Instant now) {
        require(PaymentStatus.CREATED, "fail");
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 255));
        this.failedAt = now;
        this.status = PaymentStatus.FAILED;
    }

    /** CAPTURED → REFUNDED (W4·D4). */
    public void markRefunded(Instant now) {
        require(PaymentStatus.CAPTURED, "be refunded");
        this.refundedAt = now;
        this.status = PaymentStatus.REFUNDED;
    }

    private void require(PaymentStatus expected, String action) {
        if (status != expected) {
            throw new IllegalPaymentTransitionException(status, action);
        }
    }

    public Long getBookingId() { return bookingId; }
    public int getAttempt() { return attempt; }
    public GatewayName getGateway() { return gateway; }
    public String getGatewayOrderId() { return gatewayOrderId; }
    public String getGatewayPaymentId() { return gatewayPaymentId; }
    public long getAmountPaise() { return amountPaise; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCapturedAt() { return capturedAt; }
    public Instant getFailedAt() { return failedAt; }
    public Instant getRefundedAt() { return refundedAt; }
    public long getVersion() { return version; }
}
