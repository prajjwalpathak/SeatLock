package com.seatlock.realtime;

import com.seatlock.catalog.domain.ShowSeatStatus;
import java.time.Instant;
import java.util.List;

/**
 * What a seat-map viewer receives on {@code /topic/shows/{showId}/seats}.
 *
 * @param version       per-show, +1 for every message; a gap (or a smaller number after a Redis restart)
 *                      means the client missed something and must re-fetch the seat map (W5·D2/D3)
 * @param holdExpiresAt only for HELD by a hold: the seats are AVAILABLE again from this instant on,
 *                      unless a later message says otherwise
 */
public record SeatEventMessage(long showId, long version, ShowSeatStatus status, List<Long> seatIds,
                               Instant holdExpiresAt, Instant occurredAt) {
}
