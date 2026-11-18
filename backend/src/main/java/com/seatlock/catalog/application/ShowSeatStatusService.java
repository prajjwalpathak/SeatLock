package com.seatlock.catalog.application;

import java.util.Collection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Write side of {@code show_seats.status}, for the booking module (it may not touch catalog tables).
 * Each method is one conditional UPDATE and joins the caller's transaction, so a booking and its seat
 * statuses commit or roll back together. The {@code WHERE status = …} makes every change a compare-and-set:
 * the returned count tells the caller whether all seats were still in the expected state.
 */
@Service
public class ShowSeatStatusService {

    private final JdbcClient jdbc;

    public ShowSeatStatusService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** AVAILABLE → HELD (a booking now claims them). Returns how many seats changed. */
    public int markHeld(long showId, Collection<Long> seatIds) {
        return move(showId, seatIds, "AVAILABLE", "HELD");
    }

    /** HELD → BOOKED (the booking was paid). Seats stay BOOKED for good; a refund frees them again (W4·D4). */
    public int markBooked(long showId, Collection<Long> seatIds) {
        return move(showId, seatIds, "HELD", "BOOKED");
    }

    /** HELD → AVAILABLE (booking expired or cancelled). */
    public int markAvailable(long showId, Collection<Long> seatIds) {
        return move(showId, seatIds, "HELD", "AVAILABLE");
    }

    private int move(long showId, Collection<Long> seatIds, String from, String to) {
        if (seatIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE show_seats SET status = :to, version = version + 1
                        WHERE show_id = :showId AND seat_id IN (:seatIds) AND status = :from
                        """)
                .param("to", to)
                .param("from", from)
                .param("showId", showId)
                .param("seatIds", seatIds)
                .update();
    }
}
