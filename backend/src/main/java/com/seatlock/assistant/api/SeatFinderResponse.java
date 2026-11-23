package com.seatlock.assistant.api;

import com.seatlock.assistant.seatfinder.SeatFinderService;
import com.seatlock.assistant.seatfinder.SeatLayout;
import com.seatlock.assistant.seatfinder.SeatOption;
import java.util.List;

/**
 * Up to 3 options, best first; empty when nothing matches. {@code seatIds} go straight into
 * {@code POST /shows/{id}/holds}. Nothing is held by this call.
 */
public record SeatFinderResponse(long showId, boolean holdsLive, List<Option> options) {

    public record Option(int rank, int score, String rowLabel, List<String> sections, List<Long> seatIds,
                         List<Seat> seats, long totalPricePaise, boolean hasAisleSeat) {}

    public record Seat(long seatId, String section, String rowLabel, int seatNumber, long pricePaise, boolean aisle) {}

    static SeatFinderResponse from(SeatFinderService.Result result) {
        return new SeatFinderResponse(result.showId(), result.holdsLive(),
                result.options().stream().map(SeatFinderResponse::option).toList());
    }

    private static Option option(SeatOption o) {
        return new Option(o.rank(), o.score(), o.rowLabel(), o.sections(), o.seatIds(),
                o.seats().stream().map(SeatFinderResponse::seat).toList(), o.totalPricePaise(), o.hasAisleSeat());
    }

    private static Seat seat(SeatLayout.Seat s) {
        return new Seat(s.seatId(), s.section(), s.rowLabel(), s.seatNumber(), s.pricePaise(), s.aisle());
    }
}
