package com.seatlock.booking.api;

import com.seatlock.booking.hold.Hold;
import java.time.Instant;
import java.util.List;

/** A live hold. Prices are the server's, in paise; {@code expiresAt} is UTC (the UI shows a countdown). */
public record HoldResponse(String holdId, long showId, List<Seat> seats, long totalPaise, Instant expiresAt) {

    public record Seat(long seatId, String section, String rowLabel, int seatNumber, long pricePaise) {}

    public static HoldResponse from(Hold hold) {
        return new HoldResponse(hold.holdId(), hold.showId(),
                hold.seats().stream()
                        .map(s -> new Seat(s.seatId(), s.section(), s.rowLabel(), s.seatNumber(), s.pricePaise()))
                        .toList(),
                hold.totalPaise(), hold.expiresAt());
    }
}
