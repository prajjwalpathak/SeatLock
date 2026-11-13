package com.seatlock.catalog.api;

import java.time.Instant;

/** An upcoming show of an event, as listed on the event detail page. */
public record ShowSummaryResponse(
        Long id,
        Instant startsAt,
        Long venueId,
        String venueName,
        String city,
        long fromPricePaise) {
}