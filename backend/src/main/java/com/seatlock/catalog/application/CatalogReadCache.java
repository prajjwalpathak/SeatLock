package com.seatlock.catalog.application;

import com.seatlock.catalog.api.EventDetailResponse;
import com.seatlock.catalog.api.EventSearchCriteria;
import com.seatlock.catalog.api.EventSummaryResponse;
import com.seatlock.catalog.domain.Event;
import com.seatlock.catalog.domain.EventRepository;
import com.seatlock.catalog.domain.EventStatus;
import com.seatlock.common.cache.CacheNames;
import com.seatlock.common.exception.NotFoundException;
import com.seatlock.common.web.CursorPage;
import java.time.Instant;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cached catalog reads (cache-aside via @Cacheable). A separate bean on purpose: Spring's
 * caching works through a proxy, so a @Cacheable method called from inside its own class would
 * silently skip the cache.
 * <p>Cached only where it measurably helps (docs/benchmarks.md, Run 2): event detail and event
 * list pages. Never cached: the seat map, whose single indexed query beat every cached variant,
 * and which carries live seat status (changes on every hold).</p>
 */
@Component
@Transactional(readOnly = true)
public class CatalogReadCache {

    private final EventRepository events;
    private final ShowQueryRepository showQueries;
    private final EventSearchRepository searchRepository;

    public CatalogReadCache(EventRepository events, ShowQueryRepository showQueries,
                            EventSearchRepository searchRepository) {
        this.events = events;
        this.showQueries = showQueries;
        this.searchRepository = searchRepository;
    }

    /** Published event + shows upcoming at load time (callers drop shows that have started since). */
    @Cacheable(cacheNames = CacheNames.EVENT_DETAIL, key = "#eventId")
    public EventDetailResponse eventDetail(long eventId, Instant now) {
        Event event = events.findByIdAndStatus(eventId, EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Event", eventId)); // exceptions are never cached
        return new EventDetailResponse(event.getId(), event.getTitle(), event.getDescription(),
                event.getCategory(), event.getLanguage(), event.getDurationMinutes(),
                showQueries.upcomingShows(eventId, now));
    }

    /** One list page (limit + 1 rows, so the caller can tell if there's a next page). */
    @Cacheable(cacheNames = CacheNames.EVENT_SEARCH, key = "#key")
    public CursorPage<EventSummaryResponse> searchRows(String key, EventSearchCriteria criteria, Instant from,
                                                       EventCursor after, int fetchLimit) {
        List<EventSummaryResponse> rows = searchRepository.search(criteria, from, after, fetchLimit);
        return new CursorPage<>(rows, null); // cursor is computed by the caller from the rows
    }
}
