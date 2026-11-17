package com.seatlock.booking.application;

import com.seatlock.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * What the payment module may know about a booking it is about to charge (module boundary: no entity leaves
 * the booking module). {@code bookingId} is the internal key, used only as payments.booking_id.
 * {@code amountPaise} was computed on the server from the booking's seats ({@link BookingPricing}).
 */
public record PayableBooking(long bookingId, UUID publicId, BookingStatus status,
                             long amountPaise, String currency, Instant expiresAt) {
}
