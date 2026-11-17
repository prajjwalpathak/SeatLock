package com.seatlock.booking.application;

import com.seatlock.booking.domain.Booking;
import com.seatlock.booking.domain.BookingSeat;

/**
 * Server-side amount for a payment: the sum of the prices stored on the booking's live seats (fixed when the
 * hold was created, from catalog prices). The client never sends an amount. The sum must equal the booking's
 * stored total; a mismatch means corrupted data, so we refuse to charge rather than guess.
 */
final class BookingPricing {

    private BookingPricing() {
    }

    static long amountDue(Booking booking) {
        long sum = 0;
        int seats = 0;
        for (BookingSeat seat : booking.getSeats()) {
            if (seat.isActive()) {
                sum = Math.addExact(sum, seat.getPricePaise());
                seats++;
            }
        }
        if (seats == 0 || sum <= 0) {
            throw new IllegalStateException("Booking " + booking.getPublicId() + " has nothing to pay for");
        }
        if (sum != booking.getTotalPaise()) {
            throw new IllegalStateException("Booking " + booking.getPublicId() + " total " + booking.getTotalPaise()
                    + " doesn't match its seats (" + sum + ")");
        }
        return sum;
    }
}
