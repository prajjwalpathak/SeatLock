package com.seatlock.catalog.application;

/**
 * Port: the version of the last live seat event published for a show (0 = none yet).
 * <p>Defined here, implemented by the realtime module, so dependencies keep pointing realtime → catalog.
 * The seat map carries it so a client that missed events can resync: re-fetch the map, then apply only
 * messages with a greater version.</p>
 */
public interface SeatEventVersionLookup {

    /** May throw {@link org.springframework.dao.DataAccessException} if the store is unreachable. */
    long currentVersion(long showId);
}
