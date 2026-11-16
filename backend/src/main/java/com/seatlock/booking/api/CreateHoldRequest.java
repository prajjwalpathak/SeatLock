package com.seatlock.booking.api;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Seats to hold for one show. Only presence is checked here; count, duplicates and ownership
 * are business rules in HoldService (so they return specific codes like TOO_MANY_SEATS).
 */
public record CreateHoldRequest(@NotNull List<Long> seatIds) {
}
