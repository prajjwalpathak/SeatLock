package com.seatlock.booking.application;

import com.seatlock.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.UUID;

/** What the payment module needs to cancel a paid booking (no entity crosses the module boundary). */
public record PaidBooking(long bookingId, UUID publicId, BookingStatus status, long showId, Instant showStartsAt) {
}
