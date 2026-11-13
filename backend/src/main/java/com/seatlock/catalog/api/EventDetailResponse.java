package com.seatlock.catalog.api;

import com.seatlock.catalog.domain.EventCategory;
import java.util.List;

public record EventDetailResponse(
        Long id,
        String title,
        String description,
        EventCategory category,
        String language,
        int durationMinutes,
        List<ShowSummaryResponse> upcomingShows) {

    public EventDetailResponse {
        upcomingShows = List.copyOf(upcomingShows);
    }
}