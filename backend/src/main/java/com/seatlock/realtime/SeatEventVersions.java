package com.seatlock.realtime;

/**
 * Per-show event counter key in Redis. Incremented only by {@code publish_seat_event.lua}, in the same script
 * that publishes the event, so every instance numbers events from one sequence and Pub/Sub delivers them in
 * version order. No TTL: one small key per show. Redis runs without persistence in prod, so a restart starts
 * again at 1; clients treat a smaller version than the last one seen as "resync", like a gap.
 */
final class SeatEventVersions {

    private SeatEventVersions() {
    }

    static String key(long showId) {
        return "realtime:seat-version:show:" + showId;
    }
}
