package com.seatlock.catalog.api;

import com.seatlock.catalog.domain.ShowSeatStatus;
import java.time.Instant;
import java.util.List;

/**
 * Everything the seat-map UI needs for one show: grid size, a price legend per section,
 * and every seat with its position, price and status.
 * Status here is the database view (AVAILABLE/BOOKED); live Redis holds are merged in on W3·D1.
 */
public record ShowSeatMapResponse(
        Long showId,
        Long eventId,
        String eventTitle,
        Instant startsAt,
        VenueRef venue,
        int gridRows,
        int gridCols,
        List<SectionPrice> sections,
        List<SeatView> seats) {

    public ShowSeatMapResponse {
        sections = List.copyOf(sections);
        seats = List.copyOf(seats);
    }

    public record VenueRef(Long id, String name, String city) {}

    public record SectionPrice(String section, long pricePaise, int seatCount) {}

    public record SeatView(
            Long seatId,
            String section,
            String rowLabel,
            int seatNumber,
            int gridRow,
            int gridCol,
            boolean aisle,
            long pricePaise,
            ShowSeatStatus status) {}
}