package com.seatlock.catalog.application;

import com.seatlock.catalog.api.CreateShowRequest;
import com.seatlock.catalog.api.CreatedShowResponse;
import com.seatlock.catalog.api.OrganizerEventResponse;
import com.seatlock.catalog.domain.Event;
import com.seatlock.catalog.domain.EventRepository;
import com.seatlock.catalog.domain.EventStatus;
import com.seatlock.catalog.domain.Show;
import com.seatlock.catalog.domain.ShowRepository;
import com.seatlock.catalog.domain.Venue;
import com.seatlock.catalog.domain.VenueRepository;
import com.seatlock.catalog.events.ShowScheduledEvent;
import com.seatlock.common.exception.ConflictException;
import com.seatlock.common.exception.InvalidRequestException;
import com.seatlock.common.exception.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organizer-side catalog operations. Ownership always comes from the token's user id. */
@Service
@Transactional(readOnly = true)
public class OrganizerCatalogService {

    /** Shows must be created at least this far ahead, and at most this far. */
    static final Duration MIN_LEAD_TIME = Duration.ofHours(1);
    static final Duration MAX_LEAD_TIME = Duration.ofDays(365);

    private static final Logger log = LoggerFactory.getLogger(OrganizerCatalogService.class);

    private final OrganizerEventQueryRepository queries;
    private final EventRepository events;
    private final VenueRepository venues;
    private final ShowRepository shows;
    private final ShowCreationRepository showCreation;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public OrganizerCatalogService(OrganizerEventQueryRepository queries, EventRepository events,
                                   VenueRepository venues, ShowRepository shows,
                                   ShowCreationRepository showCreation, ApplicationEventPublisher publisher,
                                   Clock clock) {
        this.queries = queries;
        this.events = events;
        this.venues = venues;
        this.shows = shows;
        this.showCreation = showCreation;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Only the caller's own events: ownership comes from the token, never from a request parameter. */
    public List<OrganizerEventResponse> myEvents(long organizerId) {
        return queries.eventsOf(organizerId, clock.instant());
    }

    /**
     * Schedules a show of one of the organizer's events and prices every seat by section.
     * Show + all its seats are created in ONE transaction: either all of it exists or none.
     */
    @Transactional
    public CreatedShowResponse createShow(long organizerId, long eventId, CreateShowRequest request) {
        Event event = events.findById(eventId)
                .filter(e -> Objects.equals(e.getOrganizerId(), organizerId)) // someone else's event = not found
                .orElseThrow(() -> new NotFoundException("Event", eventId));
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new ConflictException("EVENT_CANCELLED", "Shows can't be added to a cancelled event.");
        }
        Instant now = clock.instant();
        if (request.startsAt().isBefore(now.plus(MIN_LEAD_TIME))) {
            throw new InvalidRequestException("SHOW_TOO_SOON", "A show must start at least 1 hour from now.");
        }
        if (request.startsAt().isAfter(now.plus(MAX_LEAD_TIME))) {
            throw new InvalidRequestException("SHOW_TOO_FAR", "A show can be scheduled at most 1 year ahead.");
        }
        Venue venue = venues.findById(request.venueId())
                .orElseThrow(() -> new InvalidRequestException("UNKNOWN_VENUE", "Venue " + request.venueId() + " does not exist."));
        requireExactSections(venue.getId(), request.sectionPricesPaise().keySet());

        Show show;
        try {
            show = shows.saveAndFlush(new Show(event, venue, request.startsAt())); // flush: hit the constraints here
        } catch (DataIntegrityViolationException ex) {
            if (isOverlap(ex)) {
                throw new ConflictException("SHOW_OVERLAP",
                        "The venue is already booked for another show during that time.");
            }
            throw ex;
        }
        int seatCount = showCreation.insertShowSeats(show.getId(), venue.getId(), request.sectionPricesPaise());
        // Observer: caches (and, from W5, live seat-map clients) react after the commit
        publisher.publishEvent(new ShowScheduledEvent(show.getId(), eventId, venue.getId()));
        log.info("Organizer {} created show {} (event {}, venue {}, {} seats)",
                organizerId, show.getId(), eventId, venue.getId(), seatCount);
        return new CreatedShowResponse(show.getId(), eventId, venue.getId(), show.getStartsAt(), show.getEndsAt(),
                seatCount);
    }

    private void requireExactSections(long venueId, Set<String> priced) {
        Set<String> expected = new TreeSet<>(showCreation.sectionsOf(venueId));
        if (!expected.equals(new TreeSet<>(priced))) {
            throw new InvalidRequestException("SECTION_PRICES_MISMATCH",
                    "Give exactly one price for each section of this venue: " + String.join(", ", expected) + ".");
        }
    }

    private static boolean isOverlap(DataIntegrityViolationException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());
        return message.contains("ex_shows_venue_no_overlap") || message.contains("uq_shows_venue_start");
    }
}
