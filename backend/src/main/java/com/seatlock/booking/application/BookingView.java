package com.seatlock.booking.application;

import com.seatlock.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A booking as the API shows it: seats with labels and the prices charged. */
public record BookingView(UUID publicId, long showId, BookingStatus status, List<Seat> seats,
                          long totalPaise, String currency, Instant expiresAt,
                          Instant confirmedAt, Instant cancelledAt) {

    public BookingView {
        seats = List.copyOf(seats);
    }

    public record Seat(long seatId, String section, String rowLabel, int seatNumber, long pricePaise) {}
}
