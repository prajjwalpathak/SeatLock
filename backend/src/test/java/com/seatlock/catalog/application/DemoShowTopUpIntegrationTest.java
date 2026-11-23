package com.seatlock.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import testsupport.IntegrationTest;

/** W8·D1: published events keep 3 upcoming shows; prices copied; taken slots skipped; idempotent. */
@IntegrationTest
class DemoShowTopUpIntegrationTest {

    private static final long PRICE = 50_000;

    @Autowired DemoShowTopUpJob job;
    @Autowired JdbcTemplate jdbc;

    long venueId;
    long organizerId;
    long maxShowIdBefore;
    final List<Long> eventIds = new ArrayList<>();
    Instant firstShow;

    @BeforeEach
    void setUp() {
        venueId = jdbc.queryForObject("SELECT id FROM venues ORDER BY id LIMIT 1", Long.class);
        organizerId = jdbc.queryForObject("SELECT id FROM users WHERE role = 'ORGANIZER' ORDER BY id LIMIT 1", Long.class);
        maxShowIdBefore = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM shows", Long.class);
        // 02:00 UTC: seed shows run 14:00–18:30 UTC, so these never overlap them
        firstShow = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(2, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
    }

    @AfterEach
    void cleanUp() {
        // everything this test made, plus anything the job created for other events during it
        jdbc.update("DELETE FROM show_seats WHERE show_id > ?", maxShowIdBefore);
        jdbc.update("DELETE FROM shows WHERE id > ?", maxShowIdBefore);
        for (long id : eventIds) {
            jdbc.update("DELETE FROM show_seats WHERE show_id IN (SELECT id FROM shows WHERE event_id = ?)", id);
            jdbc.update("DELETE FROM shows WHERE event_id = ?", id);
            jdbc.update("DELETE FROM events WHERE id = ?", id);
        }
    }

    @Test
    void anEventWithOneUpcomingShowGetsTwoMoreAWeekApartWithTheSamePrices() {
        long event = eventWithShowAt(firstShow);
        int seatsPerShow = seatsOfVenue();

        job.topUp();

        assertThat(upcomingStarts(event)).containsExactly(firstShow, plusDays(firstShow, 7), plusDays(firstShow, 14));
        for (long show : showIds(event)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM show_seats WHERE show_id = ? AND price_paise = ?",
                    Integer.class, show, PRICE)).isEqualTo(seatsPerShow);
            assertThat(jdbc.queryForObject("SELECT ends_at FROM shows WHERE id = ?", Timestamp.class, show).toInstant())
                    .isEqualTo(jdbc.queryForObject("SELECT starts_at FROM shows WHERE id = ?", Timestamp.class, show)
                            .toInstant().plus(60 + 30, ChronoUnit.MINUTES));
        }

        assertThat(job.topUp()).isZero();                 // nothing left to do: a second run changes nothing
        assertThat(showIds(event)).hasSize(3);
    }

    @Test
    void aTakenSlotMovesTheNewShowToTheNextDay() {
        long event = eventWithShowAt(firstShow);
        eventWithShowAt(plusDays(firstShow, 7));          // another event already has our +7 days slot at this venue

        job.topUp();

        assertThat(upcomingStarts(event))
                .containsExactly(firstShow, plusDays(firstShow, 8), plusDays(firstShow, 15));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM shows a JOIN shows b ON a.venue_id = b.venue_id AND a.id < b.id
                WHERE a.status = 'SCHEDULED' AND b.status = 'SCHEDULED'
                  AND tstzrange(a.starts_at, a.ends_at) && tstzrange(b.starts_at, b.ends_at)
                """, Integer.class)).isZero();                 // no overlapping shows anywhere
    }

    @Test
    void draftEventsAreLeftAlone() {
        long event = eventWithShowAt(firstShow);
        jdbc.update("UPDATE events SET status = 'DRAFT' WHERE id = ?", event);

        job.topUp();

        assertThat(showIds(event)).hasSize(1);
    }

    private long eventWithShowAt(Instant startsAt) {
        long event = jdbc.queryForObject("""
                INSERT INTO events (organizer_id, title, description, category, language, duration_minutes, status)
                VALUES (?, 'Top-up test', 'Created by DemoShowTopUpIntegrationTest', 'COMEDY', 'English', 60, 'PUBLISHED')
                RETURNING id
                """, Long.class, organizerId);
        eventIds.add(event);
        long show = jdbc.queryForObject("""
                INSERT INTO shows (event_id, venue_id, starts_at, ends_at) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, event, venueId, Timestamp.from(startsAt),
                Timestamp.from(startsAt.plus(90, ChronoUnit.MINUTES)));
        jdbc.update("INSERT INTO show_seats (show_id, seat_id, price_paise) SELECT ?, id, ? FROM seats WHERE venue_id = ?",
                show, PRICE, venueId);
        return event;
    }

    private int seatsOfVenue() {
        return jdbc.queryForObject("SELECT count(*) FROM seats WHERE venue_id = ?", Integer.class, venueId);
    }

    private List<Long> showIds(long event) {
        return jdbc.queryForList("SELECT id FROM shows WHERE event_id = ? ORDER BY starts_at", Long.class, event);
    }

    private List<Instant> upcomingStarts(long event) {
        return jdbc.queryForList("""
                        SELECT starts_at FROM shows WHERE event_id = ? AND status = 'SCHEDULED' AND starts_at > now()
                        ORDER BY starts_at
                        """, Timestamp.class, event)
                .stream().map(Timestamp::toInstant).toList();
    }

    private static Instant plusDays(Instant instant, int days) {
        return instant.plus(days, ChronoUnit.DAYS);
    }
}
