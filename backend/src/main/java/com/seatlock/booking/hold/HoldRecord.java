package com.seatlock.booking.hold;

import java.time.Instant;
import java.util.List;

/** What Redis knows about one live hold (hash {@code hold:id:{holdId}}). */
public record HoldRecord(String holdId, long userId, long showId, List<Long> seatIds, Instant expiresAt) {

    public HoldRecord {
        seatIds = List.copyOf(seatIds);
    }
}
