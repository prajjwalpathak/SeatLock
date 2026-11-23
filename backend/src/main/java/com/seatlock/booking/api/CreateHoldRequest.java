package com.seatlock.booking.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Seats to hold for one show. Here only presence and a sanity cap (W8·D1: bounds the work per request);
 * count, duplicates and ownership are business rules in HoldService (so they return specific codes like
 * TOO_MANY_SEATS for 7–50 seats).
 */
public record CreateHoldRequest(@NotNull @Size(max = 50) List<Long> seatIds) {
}
