package com.seatlock.assistant.seatfinder;

import java.util.List;

/**
 * The seat map as the algorithm sees it: every seat of the show (taken ones too, so row widths and centres
 * are right) with grid position, aisle flag, price and whether it can be held right now.
 * Seats in one row are adjacent when their {@code gridCol}s are consecutive; an aisle is a missing column.
 */
public record SeatLayout(List<Seat> seats) {

    public SeatLayout {
        seats = List.copyOf(seats);
    }

    public record Seat(long seatId, String section, String rowLabel, int seatNumber, int gridRow, int gridCol,
                       boolean aisle, long pricePaise, boolean available) {}
}
