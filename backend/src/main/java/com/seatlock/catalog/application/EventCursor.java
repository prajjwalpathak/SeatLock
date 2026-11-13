package com.seatlock.catalog.application;

import com.seatlock.catalog.api.EventSummaryResponse;
import com.seatlock.common.exception.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Keyset position in the event list: (next show time, event id).
 * Encoded as URL-safe Base64 so clients treat it as opaque. Full Instant precision is kept,
 * otherwise rows with sub-millisecond differences could be skipped or repeated.
 */
public record EventCursor(Instant startsAt, long eventId) {

    private static final String SEPARATOR = "|";

    public static EventCursor of(EventSummaryResponse last) {
        return new EventCursor(last.nextShowAt(), last.id());
    }

    public String encode() {
        String raw = startsAt + SEPARATOR + eventId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static EventCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf(SEPARATOR);
            return new EventCursor(Instant.parse(raw.substring(0, sep)), Long.parseLong(raw.substring(sep + 1)));
        } catch (RuntimeException e) { // bad Base64, missing separator, bad instant or number
            throw new InvalidRequestException("INVALID_CURSOR", "The pagination cursor is invalid.");
        }
    }
}