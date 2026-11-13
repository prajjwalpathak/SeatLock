package com.seatlock.catalog.application;

import com.seatlock.catalog.api.EventDetailResponse;
import com.seatlock.catalog.api.EventSearchCriteria;
import com.seatlock.catalog.api.EventSummaryResponse;
import com.seatlock.catalog.api.ShowSummaryResponse;
import com.seatlock.catalog.domain.Event;
import com.seatlock.catalog.domain.EventRepository;
import com.seatlock.catalog.domain.EventStatus;
import com.seatlock.common.exception.NotFoundException;
import com.seatlock.common.web.CursorPage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public read API of the catalog module (other modules call this, never the repositories). */
@Service
@Transactional(readOnly = true)
public class EventCatalogService {

    private final EventSearchRepository searchRepository;
    private final ShowQueryRepository showQueries;
    private final EventRepository eventRepository;
    private final Clock clock;

    public EventCatalogService(EventSearchRepository searchRepository, ShowQueryRepository showQueries,
                               EventRepository eventRepository, Clock clock) {
        this.searchRepository = searchRepository;
        this.showQueries = showQueries;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    /** A published event with its upcoming shows. Drafts/cancelled events are reported as not found. */
    public EventDetailResponse getEvent(long eventId) {
        Event event = eventRepository.findByIdAndStatus(eventId, EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Event", eventId));
        List<ShowSummaryResponse> shows = showQueries.upcomingShows(eventId, clock.instant());
        return new EventDetailResponse(event.getId(), event.getTitle(), event.getDescription(),
                event.getCategory(), event.getLanguage(), event.getDurationMinutes(), shows);
    }

    public CursorPage<EventSummaryResponse> search(EventSearchCriteria criteria) {
        Instant now = clock.instant();
        // Never list past shows, even if the client asks for an earlier start.
        Instant from = (criteria.startsAfter() == null || criteria.startsAfter().isBefore(now))
                ? now : criteria.startsAfter();
        EventCursor after = criteria.cursor() == null ? null : EventCursor.decode(criteria.cursor());
        int limit = criteria.limit();

        // Fetch one extra row: if it exists, there is a next page.
        List<EventSummaryResponse> rows = searchRepository.search(criteria, from, after, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<EventSummaryResponse> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? EventCursor.of(page.getLast()).encode() : null;
        return new CursorPage<>(page, nextCursor);
    }
}