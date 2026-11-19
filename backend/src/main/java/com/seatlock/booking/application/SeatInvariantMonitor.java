package com.seatlock.booking.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gauge {@code seatlock.seats.double_sold}: seats with more than one live booking claim, i.e. real oversells. The
 * partial unique index makes this impossible, so it must always read 0; the dashboard shows it as the headline
 * "oversells" number, computed from the data rather than from code paths (same SQL as the race tests, decision #114).
 * <p>Counted by a job (default every minute), not on every scrape: a scrape must stay cheap and must not wait on
 * Postgres. Before the first count, and if a count fails, the gauge keeps its last value ({@code -1} = not yet
 * counted). Read-only, so two instances counting in parallel is harmless.</p>
 */
@Component
public class SeatInvariantMonitor {

    static final String METER = "seatlock.seats.double_sold";

    private static final Logger log = LoggerFactory.getLogger(SeatInvariantMonitor.class);

    /** Uses ux_booking_seats_live_seat (show_id, seat_id WHERE active): an index-only scan of live claims. */
    private static final String DOUBLE_SOLD_SEATS = """
            SELECT count(*) FROM (
                SELECT 1 FROM booking_seats
                WHERE active
                GROUP BY show_id, seat_id
                HAVING count(*) > 1
            ) AS double_sold
            """;

    private final JdbcClient jdbc;
    private final AtomicLong doubleSold = new AtomicLong(-1);

    public SeatInvariantMonitor(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        Gauge.builder(METER, doubleSold, AtomicLong::get)
                .description("Seats with more than one live booking (oversells); must be 0, -1 = not counted yet")
                .register(meters);
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${seatlock.metrics.invariant-interval:PT1M}")
    public long check() {
        try {
            long count = jdbc.sql(DOUBLE_SOLD_SEATS).query(Long.class).single();
            doubleSold.set(count);
            if (count > 0) {
                log.error("INVARIANT BROKEN: {} seats have more than one live booking", count);
            }
            return count;
        } catch (DataAccessException e) {
            log.warn("Double-sold check skipped ({}); keeping the last value", e.toString());
            return doubleSold.get();
        }
    }
}
