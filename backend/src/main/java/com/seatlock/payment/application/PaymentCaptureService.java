package com.seatlock.payment.application;

import com.seatlock.booking.application.BookingService;
import com.seatlock.booking.application.BookingService.ConfirmResult;
import com.seatlock.common.exception.NotFoundException;
import com.seatlock.payment.domain.Payment;
import com.seatlock.payment.domain.PaymentRepository;
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
        /** Payment captured, but the booking expired or was cancelled first: refund needed (W4·D4). */
        CAPTURED_BOOKING_LOST,
        /** The order was already paid by a different payment id, or the attempt had failed: refund/review (W4·D4). */
        NEEDS_REVIEW
    }

    public record CaptureResult(Outcome outcome, UUID bookingId, long bookingInternalId) {}

    private final PaymentRepository payments;
    private final BookingService bookings;
    private final Clock clock;

    public PaymentCaptureService(PaymentRepository payments, BookingService bookings, Clock clock) {
        this.payments = payments;
        this.bookings = bookings;
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
        UUID bookingId = bookings.publicIdOf(payment.getBookingId());

        if (amountPaise != null && amountPaise != payment.getAmountPaise()) {
            log.error("Order {} paid {} paise but {} were due: not confirming", orderId, amountPaise,
                    payment.getAmountPaise());
            return new CaptureResult(Outcome.NEEDS_REVIEW, bookingId, payment.getBookingId());
        }

        switch (payment.getStatus()) {
            case CAPTURED, REFUNDED -> {
                if (paymentId.equals(payment.getGatewayPaymentId())) {
                    return new CaptureResult(Outcome.ALREADY_DONE, bookingId, payment.getBookingId());
                }
                log.warn("Order {} already paid by {}; second payment {} needs a refund", orderId,
                        payment.getGatewayPaymentId(), paymentId);
                return new CaptureResult(Outcome.NEEDS_REVIEW, bookingId, payment.getBookingId());
            }
            case FAILED -> {
                log.warn("Payment {} captured for order {} whose attempt had failed: review/refund", paymentId, orderId);
                return new CaptureResult(Outcome.NEEDS_REVIEW, bookingId, payment.getBookingId());
            }
            case CREATED -> payment.markCaptured(paymentId, clock.instant());
        }

        ConfirmResult confirm = bookings.confirmPayment(payment.getBookingId());
        Outcome outcome = switch (confirm) {
            case CONFIRMED -> Outcome.CONFIRMED;
            case ALREADY_CONFIRMED -> Outcome.ALREADY_DONE;
            case NOT_CONFIRMABLE -> Outcome.CAPTURED_BOOKING_LOST;
        };
        log.info("Payment {} for order {} captured: {}", paymentId, orderId, outcome);
        return new CaptureResult(outcome, bookingId, payment.getBookingId());
    }
}
