package com.seatlock.assistant.refund;

import com.seatlock.booking.application.BookingService;
import com.seatlock.booking.application.BookingView;
import com.seatlock.booking.application.PaidBooking;
import com.seatlock.payment.application.PaidCancellationService;
import com.seatlock.payment.application.RefundPolicyEngine;
import com.seatlock.payment.application.RefundProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds {@link RefundFacts} from the other modules' public services only: the booking (status, total, deadline,
 * show start) and the payment module's quote (the same numbers the "Cancel booking" button uses). The next tier is
 * asked from the same {@link RefundPolicyEngine}, one millisecond after the current tier ends. 404 for unknown
 * bookings or someone else's (from BookingService). An unpaid booking past its deadline counts as expired even
 * before the sweeper has run (same rule as the booking page).
 */
@Service
public class RefundFactsService {

    private final BookingService bookings;
    private final PaidCancellationService cancellations;
    private final RefundPolicyEngine policy;
    private final Set<Integer> policyPercents;
    private final Clock clock;

    public RefundFactsService(BookingService bookings, PaidCancellationService cancellations, RefundPolicyEngine policy,
                              RefundProperties refundProperties, Clock clock) {
        this.bookings = bookings;
        this.cancellations = cancellations;
        this.policy = policy;
        this.policyPercents = refundProperties.policy().stream().map(RefundProperties.Tier::percent)
                .collect(Collectors.toSet());
        this.clock = clock;
    }

    public RefundFacts factsFor(long userId, String bookingPublicId) {
        BookingView booking = bookings.getBooking(userId, bookingPublicId);
        Instant showStartsAt = bookings.paidBooking(userId, bookingPublicId).showStartsAt();
        return switch (booking.status()) {
            case HELD, PAYMENT_PENDING -> clock.instant().isBefore(booking.expiresAt())
                    ? simple(RefundSituation.UNPAID, booking, showStartsAt, booking.expiresAt())
                    : simple(RefundSituation.EXPIRED, booking, showStartsAt, null);
            case EXPIRED -> simple(RefundSituation.EXPIRED, booking, showStartsAt, null);
            case CANCELLED -> simple(RefundSituation.CANCELLED, booking, showStartsAt, null);
            case REFUNDED -> simple(RefundSituation.REFUNDED, booking, showStartsAt, null);
            case CONFIRMED -> confirmed(userId, bookingPublicId, booking, showStartsAt);
        };
    }

    private RefundFacts confirmed(long userId, String publicId, BookingView booking, Instant showStartsAt) {
        PaidCancellationService.QuoteView q = cancellations.quote(userId, publicId);
        if (!q.cancellable()) {
            return new RefundFacts(RefundSituation.PAID_CLOSED, booking.totalPaise(), q.paidPaise(), 0, null, null,
                    null, null, showStartsAt, null, q.rule(), policyPercents);
        }
        Integer nextPercent = null;
        Long nextRefund = null;
        if (q.nextChangeAt() != null) {
            // at nextChangeAt itself the current tier still applies (inclusive bounds): ask just after it
            RefundPolicyEngine.Quote next = policy.quote(q.paidPaise(), showStartsAt, q.nextChangeAt().plus(Duration.ofMillis(1)));
            nextPercent = next.cancellable() ? next.percent() : 0;
            nextRefund = next.cancellable() ? next.refundPaise() : null;
        }
        return new RefundFacts(RefundSituation.PAID_CANCELLABLE, booking.totalPaise(), q.paidPaise(), q.percent(),
                q.refundPaise(), q.nextChangeAt(), nextPercent, nextRefund, showStartsAt, null, q.rule(), policyPercents);
    }

    private RefundFacts simple(RefundSituation situation, BookingView booking, Instant showStartsAt, Instant payBy) {
        return new RefundFacts(situation, booking.totalPaise(), null, 0, null, null, null, null, showStartsAt, payBy,
                null, policyPercents);
    }
}
