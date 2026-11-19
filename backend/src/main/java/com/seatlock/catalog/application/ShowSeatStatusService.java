package com.seatlock.catalog.application;

import com.seatlock.catalog.domain.ShowSeatStatus;
import com.seatlock.catalog.events.SeatsChangedEvent;
import java.util.Collection;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Write side of {@code show_seats.status}, for the booking module (it may not touch catalog tables).
 * Each method is one conditional UPDATE and joins the caller's transaction, so a booking and its seat
 * statuses commit or roll back together. The {@code WHERE status = …} makes every change a compare-and-set:
 * the returned count tells the caller whether all seats were still in the expected state.
 * <p>Every change publishes a {@link SeatsChangedEvent} with exactly the seats that moved ({@code RETURNING});
 * it reaches the live seat map only after the caller's transaction commits.</p>
 */
@Service
public class ShowSeatStatusService {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public ShowSeatStatusService(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** AVAILABLE → HELD (a booking now claims them). Returns how many seats changed. */
    public int markHeld(long showId, Collection<Long> seatIds) {
        return move(showId, seatIds, ShowSeatStatus.AVAILABLE, ShowSeatStatus.HELD);
    }

    /** HELD → BOOKED (the booking was paid). Seats stay BOOKED for good; a refund frees them again (W4·D4). */
    public int markBooked(long showId, Collection<Long> seatIds) {
        return move(showId, seatIds, ShowSeatStatus.HELD, ShowSeatStatus.BOOKED);
    }

    /** BOOKED → AVAILABLE (a paid booking was cancelled and refunded): back on sale. */
    public int releaseBooked(long showId, Collection<Long> seatIds) {
        return move(showId, seatIds, ShowSeatStatus.BOOKED, ShowSeatStatus.AVAILABLE);
    }

    /** HELD → AVAILABLE (booking expired or cancelled). */
    public int markAvailable(long showId, Collection<Long> seatIds) {
        return move(showId, seatIds, ShowSeatStatus.HELD, ShowSeatStatus.AVAILABLE);
    }

    private int move(long showId, Collection<Long> seatIds, ShowSeatStatus from, ShowSeatStatus to) {
        if (seatIds.isEmpty()) {
            return 0;
        }
        List<Long> moved = jdbc.sql("""
                        UPDATE show_seats SET status = :to, version = version + 1
                        WHERE show_id = :showId AND seat_id IN (:seatIds) AND status = :from
                        RETURNING seat_id
                        """)
                .param("to", to.name())
                .param("from", from.name())
                .param("showId", showId)
                .param("seatIds", seatIds)
                .query(Long.class)
                .list();
        if (!moved.isEmpty()) {
            events.publishEvent(SeatsChangedEvent.of(showId, moved, to));
        }
        return moved.size();
    }
}
