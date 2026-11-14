package com.seatlock.catalog.domain;

import com.seatlock.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/** One scheduled performance of an event at a venue. */
@Entity
@Table(name = "shows")
public class Show extends AuditableEntity {

    /** Cleaning/turnover time after a show before the venue can host the next one. */
    public static final Duration TURNOVER = Duration.ofMinutes(30);

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** starts_at + event duration + turnover; the DB forbids overlapping ranges per venue (V6). */
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShowStatus status;

    protected Show() {
        // for JPA
    }

    public Show(Event event, Venue venue, Instant startsAt) {
        this.event = event;
        this.venue = venue;
        this.startsAt = startsAt;
        this.endsAt = occupiedUntil(startsAt, event.getDurationMinutes());
        this.status = ShowStatus.SCHEDULED;
    }

    public Event getEvent() { return event; }
    public Venue getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public ShowStatus getStatus() { return status; }

    public static Instant occupiedUntil(Instant startsAt, int durationMinutes) {
        return startsAt.plus(Duration.ofMinutes(durationMinutes)).plus(TURNOVER);
    }
}
