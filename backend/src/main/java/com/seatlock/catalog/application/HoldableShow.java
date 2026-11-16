package com.seatlock.catalog.application;

import com.seatlock.catalog.domain.ShowSeatStatus;
import java.time.Instant;
import java.util.List;

/**
 * What the booking module needs to validate a hold: the show's start time and the requested seats
 * that belong to it, with their price and database status. Seats not in the show are simply absent.
 */
public record HoldableShow(long showId, Instant startsAt, List<Seat> seats) {

    public HoldableShow {
        seats = List.copyOf(seats);
    }

    public record Seat(long seatId, String section, String rowLabel, int seatNumber,
                       long pricePaise, ShowSeatStatus status) {}
}
