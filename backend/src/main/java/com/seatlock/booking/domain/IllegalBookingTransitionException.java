package com.seatlock.booking.domain;

import com.seatlock.common.exception.ConflictException;
import java.util.Map;

/** 409 {@code INVALID_BOOKING_TRANSITION}: e.g. confirming an expired booking. Carries status + action. */
public class IllegalBookingTransitionException extends ConflictException {

    private final BookingStatus from;
    private final String action;

    private IllegalBookingTransitionException(BookingStatus from, String action) {
        super("INVALID_BOOKING_TRANSITION", "A " + from + " booking can't " + action);
        this.from = from;
        this.action = action;
    }

    static IllegalBookingTransitionException of(BookingStatus from, String action) {
        return new IllegalBookingTransitionException(from, action);
    }

    public BookingStatus getFrom() { return from; }
    public String getAction() { return action; }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("status", from.name(), "action", action);
    }
}
