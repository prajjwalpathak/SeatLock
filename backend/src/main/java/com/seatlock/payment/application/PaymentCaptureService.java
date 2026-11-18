package com.seatlock.payment.application;

import com.seatlock.booking.application.BookingService;
import com.seatlock.booking.application.BookingService.ConfirmResult;
import com.seatlock.common.exception.NotFoundException;
import com.seatlock.payment.domain.Payment;
import com.seatlock.payment.domain.PaymentRepository;
import com.seatlock.payment.domain.RefundReason;
import com.seatlock.payment.gateway.GatewayName;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "The provider says this order was paid" → payment CAPTURED + booking CONFIRMED, in <b>one transaction</b>.
 * Called by the checkout callback and by the webhook; whichever comes first does the work, the other finds it
 * done (idempotent). Callers must have verified the provider's signature first.
 * <p>Lock order: payment row, then booking row ({@link BookingService#confirmPayment}). Creating orders locks only
 * the booking and inserts a new payment row, the sweeper and cancel lock only the booking, so there is no cycle.</p>
 */
@Service
public class PaymentCaptureService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCaptureService.class);

    /** What the capture led to; callers turn it into an HTTP answer. */
    public enum Outcome {
        /** Payment captured and booking confirmed by this call. */
        CONFIRMED,
        /** Same payment already processed (duplicate callback / webhook). */
        ALREADY_DONE,
        /** Payment captured, but the booking expired or was cancelled first: a full refund is queued. */
        CAPTURED_BOOKING_LOST,
        /**
         * Not confirmed, look at it: a second payment for an already paid order (that payment is refunded
         * automatically), a reported amount that doesn't match, or a capture on a FAILED attempt.
         */
        NEEDS_REVIEW
    }

    public record CaptureResult(Outcome outcome, UUID bookingId, long bookingInternalId) {}

    private final PaymentRepository payments;
    private final BookingService bookings;
    private final RefundService refunds;
    private final Clock clock;

    public PaymentCaptureService(PaymentRepository payments, BookingService bookings, RefundService refunds,
                                 Clock clock) {
        this.payments = payments;
        this.bookings = bookings;
        this.refunds = refunds;
        this.clock = clock;
    }

    /**
     * @param amountPaise the amount the provider reports, or null when unknown (checkout callback). A different
     *                    amount than the order's is refused: we never confirm on a partial payment.
     */
    @Transactional
    public CaptureResult capture(GatewayName gateway, String orderId, String paymentId, Long amountPaise) {
        Payment payment = payments.findForUpdateByGatewayOrder(gateway, orderId)
                .orElseThrow(() -> new NotFoundException("Payment order", orderId));
        long bookingId = payment.getBookingId();
        // Don't load the Booking before confirmPayment() locks it: an unlocked copy in the persistence context would
        // clash with the newer version the locked read returns (Hibernate refuses to merge them, FAILURES F5).

        if (amountPaise != null && amountPaise != payment.getAmountPaise()) {
            log.error("Order {} paid {} paise but {} were due: not confirming", orderId, amountPaise,
                    payment.getAmountPaise());
            return result(Outcome.NEEDS_REVIEW, bookingId);
        }

        switch (payment.getStatus()) {
            case CAPTURED, REFUNDED -> {
                if (paymentId.equals(payment.getGatewayPaymentId())) {
                    return result(Outcome.ALREADY_DONE, bookingId);
                }
                log.warn("Order {} already paid by {}; second payment {} is refunded", orderId,
                        payment.getGatewayPaymentId(), paymentId);
                refunds.request(payment, paymentId, amountPaise != null ? amountPaise : payment.getAmountPaise(),
                        RefundReason.DUPLICATE_PAYMENT);
                return result(Outcome.NEEDS_REVIEW, bookingId);
            }
            case FAILED -> {
                log.warn("Payment {} captured for order {} whose attempt had failed: review/refund", paymentId, orderId);
                return result(Outcome.NEEDS_REVIEW, bookingId);
            }
            case CREATED -> payment.markCaptured(paymentId, clock.instant());
        }

        ConfirmResult confirm = bookings.confirmPayment(bookingId); // locks the booking row
        Outcome outcome = switch (confirm) {
            case CONFIRMED -> Outcome.CONFIRMED;
            case ALREADY_CONFIRMED -> Outcome.ALREADY_DONE;
            case NOT_CONFIRMABLE -> Outcome.CAPTURED_BOOKING_LOST;
        };
        if (outcome == Outcome.CAPTURED_BOOKING_LOST) {
            // Money taken, seats gone: the full amount goes back. Queued in this transaction, sent by the worker.
            refunds.request(payment, paymentId, payment.getAmountPaise(), RefundReason.LATE_PAYMENT);
        }
        log.info("Payment {} for order {} captured: {}", paymentId, orderId, outcome);
        return result(outcome, bookingId);
    }

    /**
     * {@code payment.failed}: record the reason on the order; it stays CREATED so the user can retry (the same order
     * in Checkout, or "Pay" again, which returns this open order). Never turns a paid order back into a failed one.
     * @return false if the order isn't ours
     */
    @Transactional
    public boolean recordFailedAttempt(GatewayName gateway, String orderId, String paymentId, String reason) {
        return payments.findForUpdateByGatewayOrder(gateway, orderId)
                .map(payment -> {
                    payment.recordFailedAttempt(paymentId + ": " + reason, clock.instant());
                    return true;
                })
                .orElse(false);
    }

    /** Booking public id looked up last: by now the booking is either locked in this context or not touched again. */
    private CaptureResult result(Outcome outcome, long bookingId) {
        return new CaptureResult(outcome, bookings.publicIdOf(bookingId), bookingId);
    }
}
