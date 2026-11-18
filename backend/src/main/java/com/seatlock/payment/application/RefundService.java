package com.seatlock.payment.application;

import com.seatlock.payment.domain.Payment;
import com.seatlock.payment.domain.Refund;
import com.seatlock.payment.domain.RefundReason;
import com.seatlock.payment.domain.RefundRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes refunds into the outbox (V10) inside the caller's transaction, so the decision and the refund commit
 * together. Sending them is {@link RefundProcessor}'s job. One refund per provider payment and reason: asking twice
 * returns the existing row (the caller holds the payment row lock, and {@code uq_refunds_payment_reason} backs it up).
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refunds;
    private final Clock clock;

    public RefundService(RefundRepository refunds, Clock clock) {
        this.refunds = refunds;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Refund request(Payment payment, String gatewayPaymentId, long amountPaise, RefundReason reason) {
        return refunds.findByGatewayPaymentIdAndReason(gatewayPaymentId, reason).orElseGet(() -> {
            Refund refund = refunds.save(new Refund(payment.getId(), gatewayPaymentId, amountPaise, reason, clock.instant()));
            log.info("Refund {} queued: {} paise of {} ({})", refund.getReceipt(), amountPaise, gatewayPaymentId, reason);
            return refund;
        });
    }
}
