package com.seatlock.catalog.application;

import com.seatlock.catalog.api.EventDetailResponse;
import com.seatlock.catalog.api.EventSearchCriteria;
import com.seatlock.catalog.api.EventSummaryResponse;
import com.seatlock.catalog.api.ShowSummaryResponse;
import com.seatlock.common.web.CursorPage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Public read API of the catalog module (other modules call this, never the repositories).
 * Reads go through {@link CatalogReadCache}; this class adds what must be computed per request.
 * <p>Not @Transactional on purpose: a cache hit needs no database at all. The DB work (and its
 * read-only transaction) happens inside CatalogReadCache, only on a miss.</p>
 */
@Service
public class EventCatalogService {

    private final CatalogReadCache cache;
    private final Clock clock;

    public EventCatalogService(CatalogReadCache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    /** A published event with its upcoming shows. Drafts/cancelled events are reported as not found. */
    public EventDetailResponse getEvent(long eventId) {
        Instant now = clock.instant();
        EventDetailResponse cached = cache.eventDetail(eventId, now);
        // The cached copy may be up to 5 min old: drop shows that have started since it was loaded.
        List<ShowSummaryResponse> upcoming = cached.upcomingShows().stream()
                .filter(show -> !show.startsAt().isBefore(now))
                .toList();
        return upcoming.size() == cached.upcomingShows().size() ? cached
                : new EventDetailResponse(cached.id(), cached.title(), cached.description(), cached.category(),
                        cached.language(), cached.durationMinutes(), upcoming);
    }

    public CursorPage<EventSummaryResponse> search(EventSearchCriteria criteria) {
        Instant now = clock.instant();
        // Never list past shows, even if the client asks for an earlier start.
        boolean fromNow = criteria.startsAfter() == null || criteria.startsAfter().isBefore(now);
        Instant from = fromNow ? now : criteria.startsAfter();
        EventCursor after = criteria.cursor() == null ? null : EventCursor.decode(criteria.cursor()); // 400 before caching
        int limit = criteria.limit();

        // Fetch one extra row: if it exists, there is a next page. Cached for 60 s per criteria.
        List<EventSummaryResponse> rows = cache.searchRows(searchKey(criteria, fromNow), criteria, from, after, limit + 1)
                .items();
        boolean hasMore = rows.size() > limit;
        List<EventSummaryResponse> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? EventCursor.of(page.getLast()).encode() : null;
        return new CursorPage<>(page, nextCursor);
    }

    /**
     * Cache key for one list page: every filter, normalized like the SQL compares them (city and
     * language case-insensitively) and URL-encoded, so a crafted "q" can't impersonate other fields.
     */
    static String searchKey(EventSearchCriteria c, boolean fromNow) {
        return String.join("|",
                "q=" + enc(c.q()),
                "city=" + enc(c.city() == null ? null : c.city().toLowerCase(Locale.ROOT)),
                "cat=" + (c.category() == null ? "" : c.category().name()),
                "lang=" + enc(c.language() == null ? null : c.language().toLowerCase(Locale.ROOT)),
                "after=" + (fromNow ? "" : c.startsAfter()),
                "before=" + (c.startsBefore() == null ? "" : c.startsBefore()),
                "cursor=" + enc(c.cursor()),
                "limit=" + c.limit());
    }

    private static String enc(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
