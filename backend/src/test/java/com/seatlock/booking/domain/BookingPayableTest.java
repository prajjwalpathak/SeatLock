package com.seatlock.booking.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which states may start a (new) payment attempt. */
class BookingPayableTest {

    static final Instant EXPIRES = Instant.parse("2026-12-01T10:08:00Z");
    static final Instant BEFORE = EXPIRES.minusSeconds(60);

    private static Booking held() {
        Booking b = new Booking(1L, 2L, UUID.randomUUID(), EXPIRES);
        b.addSeat(10L, 50_000);
        return b;
    }

    @Test
    void heldAndPaymentPendingArePayable() {
        Booking b = held();
        assertThatCode(b::requirePayable).doesNotThrowAnyException();
        b.startPayment(BEFORE);
        assertThatCode(b::requirePayable).doesNotThrowAnyException(); // retry after a failed attempt
    }

    @Test
    void cancelledConfirmedAndExpiredAreNot() {
        Booking cancelled = held();
        cancelled.cancel(BEFORE);
        Booking confirmed = held();
        confirmed.startPayment(BEFORE);
        confirmed.confirm(BEFORE);
        Booking expired = held();
        expired.expire(EXPIRES);

        for (Booking b : new Booking[] {cancelled, confirmed, expired}) {
            assertThatThrownBy(b::requirePayable)
                    .isInstanceOf(IllegalBookingTransitionException.class)
                    .hasMessageContaining("startPayment");
        }
    }
}
