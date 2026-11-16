package com.seatlock.booking.hold;

import java.time.Instant;
import java.util.List;

/** A live hold as returned to callers: seats with the prices charged, total in paise, expiry in UTC. */
public record Hold(String holdId, long showId, List<HeldSeat> seats, long totalPaise, Instant expiresAt) {

    public Hold {
        seats = List.copyOf(seats);
    }

    public record HeldSeat(long seatId, String section, String rowLabel, int seatNumber, long pricePaise) {}
}
