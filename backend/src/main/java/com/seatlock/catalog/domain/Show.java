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
import java.time.Instant;

/** One scheduled performance of an event at a venue. */
@Entity
@Table(name = "shows")
public class Show extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

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
        this.status = ShowStatus.SCHEDULED;
    }

    public Event getEvent() { return event; }
    public Venue getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public ShowStatus getStatus() { return status; }
}