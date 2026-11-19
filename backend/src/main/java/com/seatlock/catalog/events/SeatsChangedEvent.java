package com.seatlock.catalog.events;

import com.seatlock.catalog.domain.ShowSeatStatus;
import java.time.Instant;
import java.util.List;

/**
 * Seats of one show now have {@code status} (Observer: published by whoever changed them, broadcast to
 * seat-map viewers by the realtime module). Published inside the changing transaction when there is one;
 * listeners act only AFTER_COMMIT, so a rolled-back change is never announced.
 *
 * @param holdExpiresAt set only for a Redis/optimistic hold (HELD with a TTL): the seats turn AVAILABLE
 *                      again at that instant without any further event, so clients can expire them locally
 */
public record SeatsChangedEvent(long showId, List<Long> seatIds, ShowSeatStatus status, Instant holdExpiresAt) {

    public SeatsChangedEvent {
        seatIds = List.copyOf(seatIds);
    }

    public static SeatsChangedEvent of(long showId, List<Long> seatIds, ShowSeatStatus status) {
        return new SeatsChangedEvent(showId, seatIds, status, null);
    }
}
