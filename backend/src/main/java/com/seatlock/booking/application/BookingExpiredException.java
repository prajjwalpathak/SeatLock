package com.seatlock.booking.application;

import com.seatlock.common.exception.ConflictException;

/** 409 {@code BOOKING_EXPIRED}: the time to pay is over (even if the sweeper hasn't marked it EXPIRED yet). */
public class BookingExpiredException extends ConflictException {

    public BookingExpiredException() {
        super("BOOKING_EXPIRED", "The time to pay for this booking is over; please select seats again");
    }
}
