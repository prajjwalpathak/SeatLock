package com.seatlock.booking.domain;

import java.time.Instant;

/**
 * State pattern for the booking lifecycle. Each state object allows only its own transitions; everything
 * else falls through to the default, which throws. Adding a state means adding one class, not editing
 * switch statements spread over the code.
 * <pre>
 *   HELD ──startPayment──▶ PAYMENT_PENDING ──confirm──▶ CONFIRMED ──refund──▶ REFUNDED
 *     │                        │
 *     ├──expire / cancel──▶ EXPIRED / CANCELLED ◀──expire / cancel──┘
 * </pre>
 * EXPIRED, CANCELLED and REFUNDED are terminal. Transitions return the next state; {@link Booking}
 * applies side effects (timestamps, releasing seats) and stores the new status.
 */
sealed interface BookingState {

    BookingStatus status();

    default BookingState startPayment(Booking booking, Instant now) {
        throw IllegalBookingTransitionException.of(status(), "startPayment");
    }

    default BookingState confirm(Booking booking, Instant now) {
        throw IllegalBookingTransitionException.of(status(), "confirm");
    }

    default BookingState expire(Booking booking, Instant now) {
        throw IllegalBookingTransitionException.of(status(), "expire");
    }

    default BookingState cancel(Booking booking, Instant now) {
        throw IllegalBookingTransitionException.of(status(), "cancel");
    }

    default BookingState refund(Booking booking, Instant now) {
        throw IllegalBookingTransitionException.of(status(), "refund");
    }

    static BookingState of(BookingStatus status) {
        return switch (status) {
            case HELD -> Held.INSTANCE;
            case PAYMENT_PENDING -> PaymentPending.INSTANCE;
            case CONFIRMED -> Confirmed.INSTANCE;
            case EXPIRED -> Terminal.EXPIRED;
            case CANCELLED -> Terminal.CANCELLED;
            case REFUNDED -> Terminal.REFUNDED;
        };
    }

    /** Seats are claimed; the user hasn't started paying yet. */
    final class Held implements BookingState {
        static final Held INSTANCE = new Held();

        public BookingStatus status() { return BookingStatus.HELD; }

        @Override public BookingState startPayment(Booking b, Instant now) { return PaymentPending.INSTANCE; }
        @Override public BookingState expire(Booking b, Instant now) { return b.expireAt(now); }
        @Override public BookingState cancel(Booking b, Instant now) { return b.cancelAt(now); }
    }

    /** A payment order exists (W4); waiting for the gateway's verdict. */
    final class PaymentPending implements BookingState {
        static final PaymentPending INSTANCE = new PaymentPending();

        public BookingStatus status() { return BookingStatus.PAYMENT_PENDING; }

        @Override public BookingState confirm(Booking b, Instant now) { return b.confirmAt(now); }
        @Override public BookingState expire(Booking b, Instant now) { return b.expireAt(now); }
        @Override public BookingState cancel(Booking b, Instant now) { return b.cancelAt(now); }
    }

    /** Paid; seats stay claimed until the booking is refunded. */
    final class Confirmed implements BookingState {
        static final Confirmed INSTANCE = new Confirmed();

        public BookingStatus status() { return BookingStatus.CONFIRMED; }

        @Override public BookingState refund(Booking b, Instant now) { return b.refundAt(now); }
    }

    /** EXPIRED, CANCELLED, REFUNDED: nothing can happen any more. */
    enum Terminal implements BookingState {
        EXPIRED(BookingStatus.EXPIRED), CANCELLED(BookingStatus.CANCELLED), REFUNDED(BookingStatus.REFUNDED);

        private final BookingStatus status;

        Terminal(BookingStatus status) {
            this.status = status;
        }

        public BookingStatus status() { return status; }
    }
}
