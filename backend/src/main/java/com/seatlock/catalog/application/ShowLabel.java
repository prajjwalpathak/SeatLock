package com.seatlock.catalog.application;

import java.time.Instant;

/** What other modules show next to a show id in lists ("Coldplay · Fri 19:30 · NSCI Dome, Mumbai"). Any status. */
public record ShowLabel(long showId, Instant startsAt, long eventId, String eventTitle, String venueName, String city) {
}
