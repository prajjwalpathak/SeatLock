package com.seatlock.catalog.api;

import java.time.Instant;

public record CreatedShowResponse(long showId, long eventId, long venueId, Instant startsAt, Instant endsAt,
                                  int seatCount) {
}
