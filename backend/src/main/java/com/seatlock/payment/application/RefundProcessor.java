package com.seatlock.payment.application;

import com.seatlock.payment.domain.Payment;
import com.seatlock.payment.domain.PaymentRepository;
import com.seatlock.payment.domain.PaymentStatus;
import com.seatlock.payment.domain.Refund;
import com.seatlock.payment.domain.RefundReason;
import com.seatlock.payment.domain.RefundRepository;
import com.seatlock.payment.domain.RefundStatus;
import com.seatlock.payment.gateway.GatewayRefund;
import com.seatlock.payment.gateway.PaymentGateway;
import com.seatlock.payment.gateway.PaymentGatewayException;
import com.seatlock.payment.gateway.RefundCommand;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued refunds to the provider. Three short steps per refund, never a transaction around the network call:
 * <ol>
 *   <li><b>claim</b> (tx): lock due PENDING rows with SKIP LOCKED and push {@code next_attempt_at} out by a lease,
 *       so a second instance skips them while we work;</li>
 *   <li><b>send</b> (no tx): {@link PaymentGateway#refund} is idempotent by receipt, so a retry after a lost answer
 *       adopts the earlier refund instead of paying twice;</li>
 *   <li><b>record</b> (tx): SUBMITTED (+ payment REFUNDED for the payment we hold), or failure with backoff.</li>
 * </ol>
 * If the app dies between send and record, the lease runs out, the refund is retried, and the receipt lookup finds it.
 */
@Service
@EnableConfigurationProperties(RefundProperties.class)
public class RefundProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundProcessor.class);

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final RefundProperties properties;
    private final Clock clock;

    public RefundProcessor(RefundRepository refunds, PaymentRepository payments, PaymentGateway gateway,
                           TransactionTemplate tx, RefundProperties properties, Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.gateway = gateway;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    /** One worker run: claim a batch, send each. Returns how many were accepted by the provider. */
    public int processDue() {
        List<Long> claimed = claim();
        int submitted = 0;
        for (Long id : claimed) {
            if (send(id)) {
                submitted++;
            }
        }
        return submitted;
    }

    List<Long> claim() {
        return tx.execute(status -> {
            Instant now = clock.instant();
            List<Long> ids = refunds.lockDueIds(now, properties.batchSize());
            refunds.findAllById(ids).forEach(r -> r.claim(now, properties.lease()));
            return ids;
        });
    }

    boolean send(long refundId) {
        Refund refund = refunds.findById(refundId).orElse(null);
        if (refund == null || refund.getStatus() != RefundStatus.PENDING) {
            return false;
        }
        Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
        if (payment.getGateway() != gateway.name()) {
            fail(refundId, "payment was made via " + payment.getGateway() + ", active gateway is " + gateway.name());
            return false;
        }
        try {
            GatewayRefund done = gateway.refund(
                    new RefundCommand(refund.getGatewayPaymentId(), refund.getAmountPaise(), refund.getReceipt()));
            tx.executeWithoutResult(status -> submitted(refundId, done));
            log.info("Refund {} submitted: {} ({} paise of {}, {})", refund.getReceipt(), done.refundId(),
                    refund.getAmountPaise(), refund.getGatewayPaymentId(), refund.getReason());
            return true;
        } catch (PaymentGatewayException e) {
            fail(refundId, e.getMessage());
            return false;
        }
    }

    private void submitted(long refundId, GatewayRefund done) {
        Refund refund = refunds.findById(refundId).orElseThrow();
        Instant now = clock.instant();
        refund.markSubmitted(done.refundId(), now);
        // A duplicate payment is a different provider payment than the one this row records; ours stays CAPTURED.
        if (refund.getReason() != RefundReason.DUPLICATE_PAYMENT) {
            Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
            if (payment.getStatus() == PaymentStatus.CAPTURED) {
                payment.markRefunded(now);
            }
        }
    }

    private void fail(long refundId, String error) {
        tx.executeWithoutResult(status -> {
            Refund refund = refunds.findById(refundId).orElseThrow();
            refund.recordFailure(error, clock.instant());
            if (refund.getStatus() == RefundStatus.FAILED) {
                log.error("Refund {} FAILED after {} attempts, needs manual action: {}", refund.getReceipt(),
                        refund.getAttempts(), error);
            } else {
                log.warn("Refund {} attempt {} failed, retry at {}: {}", refund.getReceipt(), refund.getAttempts(),
                        refund.getNextAttemptAt(), error);
            }
        });
    }
}
