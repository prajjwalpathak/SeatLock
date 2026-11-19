package com.seatlock.booking.hold;

import com.seatlock.catalog.application.SeatHoldLookup;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Catalog's port for the seat-map overlay, answered by whichever {@link SeatLockingStrategy} is active. */
@Component
public class LiveHoldLookup implements SeatHoldLookup {

    private final SeatLockingStrategy strategy;

    public LiveHoldLookup(SeatLockingStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public Set<Long> heldSeatIds(long showId, Collection<Long> seatIds) {
        return strategy.heldSeatIds(showId, seatIds);
    }
}
