package com.seatlock.catalog.api;

import com.seatlock.catalog.domain.EventCategory;
import java.time.Instant;

/** One card in the event list: the event plus its next matching show and "from" price. */
public record EventSummaryResponse(
        Long id,
        String title,
        EventCategory category,
        String language,
        int durationMinutes,
        Instant nextShowAt,
        String nextShowVenue,
        String nextShowCity,
        long fromPricePaise) {
}