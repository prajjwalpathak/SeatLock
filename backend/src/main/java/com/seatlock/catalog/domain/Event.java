package com.seatlock.catalog.domain;

import com.seatlock.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "events")
public class Event extends AuditableEntity {

    /** User id from the identity module: a cross-module reference is an ID, not an association. */
    @Column(name = "organizer_id", nullable = false)
    private Long organizerId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventCategory category;

    @Column(nullable = false, length = 30)
    private String language;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    protected Event() {
        // for JPA
    }

    public Event(Long organizerId, String title, String description, EventCategory category,
                 String language, int durationMinutes) {
        this.organizerId = organizerId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.language = language;
        this.durationMinutes = durationMinutes;
        this.status = EventStatus.DRAFT;
    }

    public Long getOrganizerId() { return organizerId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public EventCategory getCategory() { return category; }
    public String getLanguage() { return language; }
    public int getDurationMinutes() { return durationMinutes; }
    public EventStatus getStatus() { return status; }
}