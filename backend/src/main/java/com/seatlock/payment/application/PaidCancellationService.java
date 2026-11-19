package com.seatlock.payment.application;

import com.seatlock.booking.application.BookingService;
import com.seatlock.booking.application.BookingView;
import com.seatlock.booking.application.PaidBooking;
import com.seatlock.booking.domain.BookingStatus;
import com.seatlock.common.exception.ConflictException;
import com.seatlock.payment.domain.Payment;
import com.seatlock.payment.domain.PaymentRepository;
import com.seatlock.payment.domain.PaymentStatus;
import com.seatlock.payment.domain.RefundReason;
import com.seatlock.payment.domain.RefundRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cancelling a CONFIRMED booking: the policy decides the refund, then one transaction locks the payment, then the
 * booking (same order as capture, ADR-011), moves the booking to REFUNDED, puts the seats back on sale and queues the
 * refund (outbox, sent by {@link RefundProcessor}). Repeating it returns the same result; a concurrent duplicate
 * waits on the payment lock and then finds the cancellation done.
 */
@Service
public class PaidCancellationService {

    public record QuoteView(boolean cancellable, int percent, long refundPaise, long paidPaise, Instant nextChangeAt,
                            String rule) {}

    public record CancellationView(BookingView booking, int percent, long refundPaise) {}

    private final BookingService bookings;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final RefundService refundService;
    private final RefundPolicyEngine policy;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaidCancellationService(BookingService bookings, PaymentRepository payments, RefundRepository refunds,
                                   RefundService refundService, RefundPolicyEngine policy, TransactionTemplate tx,
                                   Clock clock) {
        this.bookings = bookings;
        this.payments = payments;
        this.refunds = refunds;
        this.refundService = refundService;
        this.policy = policy;
        this.tx = tx;
        this.clock = clock;
    }

    /** What cancelling now would give back. Only CONFIRMED bookings can be cancelled this way. */
    public QuoteView quote(long userId, String bookingPublicId) {
        PaidBooking booking = bookings.paidBooking(userId, bookingPublicId);
        if (booking.status() != BookingStatus.CONFIRMED) {
            return new QuoteView(false, 0, 0, 0, null, "only confirmed bookings can be cancelled for a refund");
        }
        Payment payment = captured(booking.bookingId()).orElseThrow(() ->
                new IllegalStateException("Confirmed booking " + booking.publicId() + " has no captured payment"));
        RefundPolicyEngine.Quote q = policy.quote(payment.getAmountPaise(), booking.showStartsAt(), clock.instant());
        return new QuoteView(q.cancellable(), q.percent(), q.refundPaise(), payment.getAmountPaise(), q.nextChangeAt(),
                q.rule());
    }

    public CancellationView cancel(long userId, String bookingPublicId) {
        // Read part outside the transaction: nothing loaded here enters the locking transaction below (#133).
        PaidBooking booking = bookings.paidBooking(userId, bookingPublicId);
        if (booking.status() != BookingStatus.CONFIRMED && booking.status() != BookingStatus.REFUNDED) {
            throw new ConflictException("NOT_A_PAID_BOOKING",
                    "Only confirmed bookings can be cancelled for a refund; this one is " + booking.status());
        }
        int[] percent = {0};
        long[] refundPaise = {0};
        tx.executeWithoutResult(status -> {
            Payment payment = payments.findForUpdateByBookingIdAndStatus(booking.bookingId(), PaymentStatus.CAPTURED)
                    .or(() -> payments.findForUpdateByBookingIdAndStatus(booking.bookingId(), PaymentStatus.REFUNDED))
                    .orElseThrow(() -> new IllegalStateException("Booking " + booking.publicId() + " has no captured payment"));
            var existing = refunds.findByGatewayPaymentIdAndReason(payment.getGatewayPaymentId(), RefundReason.CANCELLATION);
            if (existing.isPresent()) { // done before (a retry, or the twin of a concurrent request)
                refundPaise[0] = existing.get().getAmountPaise();
                percent[0] = (int) (refundPaise[0] * 100 / payment.getAmountPaise());
                return;
            }
            RefundPolicyEngine.Quote q = policy.quote(payment.getAmountPaise(), booking.showStartsAt(), clock.instant());
            if (!q.cancellable()) {
                throw new ConflictException("CANCELLATION_CLOSED", "This booking can no longer be cancelled: " + q.rule());
            }
            bookings.refundBooking(booking.bookingId()); // booking row lock; CONFIRMED → REFUNDED, seats back on sale
            if (q.refundPaise() > 0) {
                refundService.request(payment, payment.getGatewayPaymentId(), q.refundPaise(), RefundReason.CANCELLATION);
            }
            percent[0] = q.percent();
            refundPaise[0] = q.refundPaise();
        });
        return new CancellationView(bookings.getBooking(userId, bookingPublicId), percent[0], refundPaise[0]);
    }

    private Optional<Payment> captured(long bookingId) {
        return payments.findFirstByBookingIdAndStatusIn(bookingId, EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.REFUNDED));
    }
}
