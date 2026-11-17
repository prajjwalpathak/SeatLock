package com.seatlock.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.booking.domain.Booking;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BookingPricingTest {

    private static Booking booking(long... prices) {
        Booking b = new Booking(1L, 2L, UUID.randomUUID(), Instant.parse("2026-12-01T10:08:00Z"));
        long seat = 100;
        for (long price : prices) {
            b.addSeat(seat++, price);
        }
        return b;
    }

    @Test
    void amountIsTheSumOfTheSeatPrices() {
        assertThat(BookingPricing.amountDue(booking(50_000, 50_000, 75_000))).isEqualTo(175_000);
    }

    @Test
    void aTotalThatDoesNotMatchTheSeatsIsRefused() {
        Booking b = booking(50_000);
        ReflectionTestUtils.setField(b, "totalPaise", 1L); // corrupted row: never charge a guessed amount

        assertThatThrownBy(() -> BookingPricing.amountDue(b))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("doesn't match");
    }

    @Test
    void aBookingWithoutLiveSeatsHasNothingToPay() {
        Booking cancelled = booking(50_000);
        cancelled.cancel(Instant.parse("2026-12-01T10:01:00Z")); // releases every seat

        assertThatThrownBy(() -> BookingPricing.amountDue(cancelled)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BookingPricing.amountDue(booking())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void overflowIsDetected() {
        assertThatThrownBy(() -> booking(Long.MAX_VALUE, 1)).isInstanceOf(ArithmeticException.class);
    }
}
