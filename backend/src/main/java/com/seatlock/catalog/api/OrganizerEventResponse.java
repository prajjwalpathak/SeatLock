package com.seatlock.catalog.api;

import com.seatlock.catalog.domain.EventCategory;
import com.seatlock.catalog.domain.EventStatus;
import java.time.Instant;

/** One of the organizer's own events, drafts included. {@code nextShowAt} is null if none upcoming. */
public record OrganizerEventResponse(long id, String title, EventCategory category, String language,
                                     EventStatus status, int showCount, int upcomingShowCount,
                                     Instant nextShowAt) {
}
