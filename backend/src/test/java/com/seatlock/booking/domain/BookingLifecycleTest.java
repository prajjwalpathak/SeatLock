package com.seatlock.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every status × action: the allowed ones move to the expected status, all others throw (and change nothing). */
class BookingLifecycleTest {

    private static final Instant EXPIRES = Instant.parse("2026-12-01T12:08:00Z");
    private static final Instant BEFORE = EXPIRES.minus(Duration.ofMinutes(5));
    private static final Instant AFTER = EXPIRES.plusSeconds(1);

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @CsvSource({
            // from,           action,       to (or INVALID)
            "HELD,            startPayment, PAYMENT_PENDING",
            "HELD,            confirm,      INVALID",           // must go through payment
            "HELD,            expire,       EXPIRED",
            "HELD,            cancel,       CANCELLED",
            "HELD,            refund,       INVALID",

            "PAYMENT_PENDING, startPayment, INVALID",
            "PAYMENT_PENDING, confirm,      CONFIRMED",
            "PAYMENT_PENDING, expire,       EXPIRED",
            "PAYMENT_PENDING, cancel,       CANCELLED",
            "PAYMENT_PENDING, refund,       INVALID",

            "CONFIRMED,       startPayment, INVALID",
            "CONFIRMED,       confirm,      INVALID",           // a duplicate payment webhook can't confirm twice
            "CONFIRMED,       expire,       INVALID",           // the sweeper never touches paid bookings
            "CONFIRMED,       cancel,       INVALID",           // paid → only refund
            "CONFIRMED,       refund,       REFUNDED",

            "EXPIRED,         startPayment, INVALID",
            "EXPIRED,         confirm,      INVALID",           // late payment → refunded by W4, booking stays EXPIRED
            "EXPIRED,         expire,       INVALID",
            "EXPIRED,         cancel,       INVALID",
            "EXPIRED,         refund,       INVALID",

            "CANCELLED,       startPayment, INVALID",
            "CANCELLED,       confirm,      INVALID",
            "CANCELLED,       expire,       INVALID",
            "CANCELLED,       cancel,       INVALID",
            "CANCELLED,       refund,       INVALID",

            "REFUNDED,        startPayment, INVALID",
            "REFUNDED,        confirm,      INVALID",
            "REFUNDED,        expire,       INVALID",
            "REFUNDED,        cancel,       INVALID",
            "REFUNDED,        refund,       INVALID",
    })
    void transitions(BookingStatus from, String action, String to) {
        Booking booking = bookingIn(from);

        if (to.equals("INVALID")) {
            assertThatThrownBy(() -> act(action).accept(booking, AFTER))
                    .isInstanceOfSatisfying(IllegalBookingTransitionException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("INVALID_BOOKING_TRANSITION");
                        assertThat(e.getFrom()).isEqualTo(from);
                        assertThat(e.getProperties()).containsEntry("action", action);
                    });
            assertThat(booking.getStatus()).isEqualTo(from);            // unchanged
        } else {
            act(action).accept(booking, AFTER);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.valueOf(to));
        }
    }

    @Test
    void cannotExpireBeforeExpiresAt() {
        Booking booking = bookingIn(BookingStatus.HELD);

        assertThatThrownBy(() -> booking.expire(BEFORE)).isInstanceOf(IllegalBookingTransitionException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
        booking.expire(EXPIRES);                                         // exactly at expiry is allowed
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
    }

    @Test
    void liveBookingsKeepTheirSeatsAndEndedOnesReleaseThem() {
        assertThat(bookingIn(BookingStatus.HELD).isLive()).isTrue();
        assertThat(bookingIn(BookingStatus.PAYMENT_PENDING).isLive()).isTrue();
        Booking confirmed = bookingIn(BookingStatus.CONFIRMED);
        assertThat(confirmed.isLive()).isTrue();
        assertThat(confirmed.getSeats()).allMatch(BookingSeat::isActive);
        assertThat(confirmed.getConfirmedAt()).isEqualTo(AFTER);

        for (BookingStatus ended : new BookingStatus[] {BookingStatus.EXPIRED, BookingStatus.CANCELLED, BookingStatus.REFUNDED}) {
            Booking booking = bookingIn(ended);
            assertThat(booking.isLive()).as(ended.name()).isFalse();
            assertThat(booking.getSeats()).as(ended.name()).noneMatch(BookingSeat::isActive);
        }
        assertThat(bookingIn(BookingStatus.CANCELLED).getCancelledAt()).isEqualTo(AFTER);
    }

    // ------------------------------------------------------------------ helpers

    /** Reaches each status only through legal transitions, like production would. */
    private static Booking bookingIn(BookingStatus status) {
        Booking b = new Booking(1L, 2L, UUID.randomUUID(), EXPIRES);
        b.addSeat(10L, 50_000);
        b.addSeat(11L, 50_000);
        switch (status) {
            case HELD -> { }
            case PAYMENT_PENDING -> b.startPayment(BEFORE);
            case CONFIRMED -> { b.startPayment(BEFORE); b.confirm(AFTER); }
            case EXPIRED -> b.expire(AFTER);
            case CANCELLED -> b.cancel(AFTER);
            case REFUNDED -> { b.startPayment(BEFORE); b.confirm(AFTER); b.refund(AFTER); }
        }
        return b;
    }

    private static BiConsumer<Booking, Instant> act(String action) {
        return switch (action) {
            case "startPayment" -> Booking::startPayment;
            case "confirm" -> Booking::confirm;
            case "expire" -> Booking::expire;
            case "cancel" -> Booking::cancel;
            case "refund" -> Booking::refund;
            default -> throw new IllegalArgumentException(action);
        };
    }
}
