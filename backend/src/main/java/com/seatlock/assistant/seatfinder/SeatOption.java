package com.seatlock.assistant.seatfinder;

import java.util.List;

/**
 * One suggestion: {@code seats} side by side in one row, left to right. {@code score} 0–100 (higher is better),
 * {@code rank} 1–3. Prices come from the seat map (server data), summed exactly.
 */
public record SeatOption(int rank, int score, String rowLabel, List<String> sections, List<SeatLayout.Seat> seats,
                         long totalPricePaise, boolean hasAisleSeat) {

    public SeatOption {
        sections = List.copyOf(sections);
        seats = List.copyOf(seats);
    }

    public List<Long> seatIds() {
        return seats.stream().map(SeatLayout.Seat::seatId).toList();
    }
}
