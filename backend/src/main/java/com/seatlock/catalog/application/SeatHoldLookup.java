package com.seatlock.catalog.application;

import java.util.Collection;
import java.util.Set;

/**
 * Port: which seats of a show are currently held (not yet booked).
 * <p>Defined here, implemented by the booking module ({@code booking.hold.SeatHoldReader}), so the
 * dependency points booking → catalog only. Catalog never needs to know holds live in Redis.</p>
 */
public interface SeatHoldLookup {

    /**
     * Returns the subset of {@code seatIds} that have a live hold for {@code showId}.
     * Implementations read all seats in one round trip and may throw
     * {@link org.springframework.dao.DataAccessException} if the store is unreachable.
     */
    Set<Long> heldSeatIds(long showId, Collection<Long> seatIds);
}
