package com.seatlock.catalog.events;

/**
 * Published when an organizer schedules a show (after validation, inside the transaction).
 * Listeners that must only act on committed data use @TransactionalEventListener(AFTER_COMMIT).
 */
public record ShowScheduledEvent(long showId, long eventId, long venueId) {
}
