package com.seatlock.catalog.application;

import com.seatlock.catalog.events.ShowScheduledEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the public demo alive (W8·D1): the seed shows were dated relative to the migration date, so without this
 * every event's last show would pass within weeks of the first deploy and the catalog would go empty.
 * <p>Nightly, every PUBLISHED event with fewer than {@code min-upcoming-shows} upcoming shows gets new ones: one week
 * after its latest show (or the first week after tomorrow, if that's already past), same venue, same time of day,
 * same per-seat prices (copied from that latest show). A slot taken by another show (unique start or the venue
 * no-overlap constraint) is skipped with {@code ON CONFLICT DO NOTHING} and the next day is tried.
 * <p>One transaction under {@code pg_try_advisory_xact_lock}: with 2 instances only one runs, the other logs and skips.
 * Caches are evicted after commit through the usual {@link ShowScheduledEvent}.
 */
@Component
public class DemoShowTopUpJob {

    private static final Logger log = LoggerFactory.getLogger(DemoShowTopUpJob.class);

    private static final String TRY_LOCK = "SELECT pg_try_advisory_xact_lock(hashtext('seatlock:demo-show-top-up'))";

    private static final String EVENTS_NEEDING_SHOWS = """
            SELECT count(*) FROM events e
            WHERE e.status = 'PUBLISHED'
              AND EXISTS (SELECT 1 FROM shows s WHERE s.event_id = e.id AND s.status = 'SCHEDULED')
              AND (SELECT count(*) FROM shows u
                   WHERE u.event_id = e.id AND u.status = 'SCHEDULED' AND u.starts_at > CAST(:now AS timestamptz))
                  < CAST(:minUpcoming AS int)
            """;

    // One new show per event that still needs one. The weekly step keeps weekday and time of day; extraDays moves
    // a conflicting slot to the next day on a later round.
    private static final String INSERT_NEXT_SHOWS = """
            WITH need AS (
                SELECT e.id AS event_id, e.duration_minutes, last.venue_id, last.starts_at AS last_start
                FROM events e
                JOIN LATERAL (SELECT s.venue_id, s.starts_at FROM shows s
                              WHERE s.event_id = e.id AND s.status = 'SCHEDULED'
                              ORDER BY s.starts_at DESC LIMIT 1) last ON true
                WHERE e.status = 'PUBLISHED'
                  AND (SELECT count(*) FROM shows u
                       WHERE u.event_id = e.id AND u.status = 'SCHEDULED' AND u.starts_at > CAST(:now AS timestamptz))
                      < CAST(:minUpcoming AS int)
            ), next AS (
                SELECT event_id, venue_id, duration_minutes,
                       last_start + make_interval(days =>
                           7 * greatest(1, ceil(extract(epoch FROM (CAST(:now AS timestamptz) + interval '1 day' - last_start))
                                                / 604800.0)::int)
                           + CAST(:extraDays AS int)) AS starts_at
                FROM need
            )
            INSERT INTO shows (event_id, venue_id, starts_at, ends_at)
            SELECT event_id, venue_id, starts_at, starts_at + make_interval(mins => duration_minutes + 30)
            FROM next
            ON CONFLICT DO NOTHING
            RETURNING id, event_id, venue_id
            """;

    private static final String COPY_PRICES = """
            INSERT INTO show_seats (show_id, seat_id, price_paise)
            SELECT :showId, ss.seat_id, ss.price_paise
            FROM show_seats ss
            WHERE ss.show_id = (SELECT s.id FROM shows s
                                WHERE s.event_id = :eventId AND s.venue_id = :venueId AND s.id <> :showId
                                ORDER BY s.starts_at DESC LIMIT 1)
            """;

    private record NewShow(long showId, long eventId, long venueId) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final boolean enabled;
    private final boolean jobsEnabled;
    private final int minUpcoming;

    public DemoShowTopUpJob(JdbcClient jdbc, TransactionTemplate tx, ApplicationEventPublisher events, Clock clock,
                            @Value("${seatlock.demo.reset-enabled:true}") boolean enabled,
                            @Value("${seatlock.scheduling.enabled:true}") boolean jobsEnabled,
                            @Value("${seatlock.demo.min-upcoming-shows:3}") int minUpcoming) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
        this.enabled = enabled;
        this.jobsEnabled = jobsEnabled;
        this.minUpcoming = minUpcoming;
    }

    /**
     * Also once at startup (W8·D4): the nightly run is skipped whenever the app isn't up at 03:30 (a laptop, a VM that
     * was stopped to save credit), and until the next night events could show no upcoming shows. Same advisory lock,
     * so with two instances starting together only one works; idempotent; takes milliseconds. Off where jobs are off
     * (tests: seatlock.scheduling.enabled=false).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (jobsEnabled) {
            nightly();
        }
    }

    @Scheduled(cron = "${seatlock.demo.reset-cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        if (!enabled) {
            return;
        }
        try {
            topUp();
        } catch (RuntimeException e) {
            log.warn("Demo show top-up failed; will retry tomorrow ({})", e.toString());
        }
    }

    /** @return shows created (0 when another instance holds the lock or nothing is needed) */
    public int topUp() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Integer created = tx.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.sql(TRY_LOCK).query(Boolean.class).single())) {
                log.info("Demo show top-up skipped: another instance is running it");
                return 0;
            }
            int total = 0;
            int extraDays = 0;
            for (int round = 0; round < minUpcoming * 3 + 3 && extraDays <= 2; round++) {
                long needing = jdbc.sql(EVENTS_NEEDING_SHOWS).param("now", now).param("minUpcoming", minUpcoming)
                        .query(Long.class).single();
                if (needing == 0) {
                    break;
                }
                List<NewShow> shows = jdbc.sql(INSERT_NEXT_SHOWS)
                        .param("now", now).param("minUpcoming", minUpcoming).param("extraDays", extraDays)
                        .query((rs, i) -> new NewShow(rs.getLong("id"), rs.getLong("event_id"), rs.getLong("venue_id")))
                        .list();
                for (NewShow show : shows) {
                    int seats = jdbc.sql(COPY_PRICES).param("showId", show.showId()).param("eventId", show.eventId())
                            .param("venueId", show.venueId()).update();
                    if (seats == 0) {
                        throw new IllegalStateException("Show " + show.showId() + ": no template show to copy prices from");
                    }
                    events.publishEvent(new ShowScheduledEvent(show.showId(), show.eventId(), show.venueId()));
                }
                total += shows.size();
                extraDays = shows.isEmpty() ? extraDays + 1 : 0; // only conflicts left → try the next day
            }
            return total;
        });
        int count = created == null ? 0 : created;
        if (count > 0) {
            log.info("Demo show top-up: created {} shows (at least {} upcoming per published event)", count, minUpcoming);
        }
        return count;
    }
}
