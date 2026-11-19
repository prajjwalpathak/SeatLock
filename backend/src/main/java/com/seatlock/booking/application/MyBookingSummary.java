package com.seatlock.booking.application;

import com.seatlock.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.UUID;

/** One row of "My bookings": the booking plus what the list shows about its show. */
public record MyBookingSummary(
        UUID publicId,
        BookingStatus status,
        long showId,
        Long eventId,
        String eventTitle,
        Instant showStartsAt,
        String venueName,
        String city,
        int seatCount,
        long totalPaise,
        String currency,
        Instant expiresAt,
        Instant createdAt) {
}
