package com.seatlock.booking.hold;

import java.util.List;

/** Outcome of one atomic hold attempt in Redis. */
public sealed interface HoldAttempt {

    /** Every seat is now held under {@code holdId}. */
    record Held(String holdId) implements HoldAttempt {}

    /** Nothing was written: these seats are already held by someone (or by an earlier hold of yours). */
    record SeatsTaken(List<Long> seatIds) implements HoldAttempt {
        public SeatsTaken {
            seatIds = List.copyOf(seatIds);
        }
    }

    /** Nothing was written: this user already has a live hold for this show. */
    record HoldExists(String existingHoldId) implements HoldAttempt {}
}
