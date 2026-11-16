package com.seatlock.booking.hold;

import com.seatlock.common.exception.ConflictException;
import java.util.List;

/** 409 {@code SEATS_UNAVAILABLE}: some requested seats are held or booked. Carries their IDs for the UI. */
public class SeatsUnavailableException extends ConflictException {

    private final List<Long> seatIds;

    public SeatsUnavailableException(List<Long> seatIds) {
        super("SEATS_UNAVAILABLE", "Some of the selected seats are no longer available");
        this.seatIds = List.copyOf(seatIds);
    }

    public List<Long> getSeatIds() {
        return seatIds;
    }
}
