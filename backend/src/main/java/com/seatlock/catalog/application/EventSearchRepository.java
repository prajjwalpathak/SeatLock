package com.seatlock.catalog.application;

import com.seatlock.catalog.api.EventSearchCriteria;
import com.seatlock.catalog.api.EventSummaryResponse;
import com.seatlock.catalog.domain.EventCategory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-model query for the event list. Plain SQL because each row needs a per-event
 * "next show" (LATERAL) and "from price"; loading entities would cause N+1 queries.
 * Only fixed SQL fragments are appended; every user value is a bound parameter.
 */
@Repository
public class EventSearchRepository {

    private static final String SQL = """
            SELECT e.id, e.title, e.category, e.language, e.duration_minutes,
                   ns.starts_at, ns.venue_name, ns.city, ns.from_price_paise
            FROM events e
            JOIN LATERAL (
                SELECT s.starts_at, v.name AS venue_name, v.city,
                       (SELECT min(ss.price_paise) FROM show_seats ss WHERE ss.show_id = s.id) AS from_price_paise
                FROM shows s
                JOIN venues v ON v.id = s.venue_id
                WHERE s.event_id = e.id
                  AND s.status = 'SCHEDULED'
                  AND s.starts_at >= :from
                  %s
                ORDER BY s.starts_at, s.id
                LIMIT 1
            ) ns ON true
            WHERE e.status = 'PUBLISHED'
              %s
            ORDER BY ns.starts_at, e.id
            LIMIT :limit
            """;

    private static final RowMapper<EventSummaryResponse> ROW_MAPPER = (rs, rowNum) -> new EventSummaryResponse(
            rs.getLong("id"),
            rs.getString("title"),
            EventCategory.valueOf(rs.getString("category")),
            rs.getString("language"),
            rs.getInt("duration_minutes"),
            rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
            rs.getString("venue_name"),
            rs.getString("city"),
            rs.getLong("from_price_paise"));

    private final JdbcClient jdbc;

    public EventSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param from  only shows starting at or after this instant count (already clamped to "now")
     * @param after keyset position to continue from, or null for the first page
     * @param limit maximum rows to return
     */
    public List<EventSummaryResponse> search(EventSearchCriteria criteria, Instant from,
                                             EventCursor after, int limit) {
        StringBuilder showFilters = new StringBuilder();
        StringBuilder eventFilters = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        params.put("from", utc(from));
        params.put("limit", limit);

        if (criteria.startsBefore() != null) {
            showFilters.append(" AND s.starts_at < :before");
            params.put("before", utc(criteria.startsBefore()));
        }
        if (criteria.city() != null) {
            showFilters.append(" AND lower(v.city) = lower(:city)");
            params.put("city", criteria.city());
        }
        if (criteria.category() != null) {
            eventFilters.append(" AND e.category = :category");
            params.put("category", criteria.category().name());
        }
        if (criteria.language() != null) {
            eventFilters.append(" AND lower(e.language) = lower(:language)");
            params.put("language", criteria.language());
        }
        if (after != null) {
            eventFilters.append(" AND (ns.starts_at, e.id) > (:afterTime, :afterId)");
            params.put("afterTime", utc(after.startsAt()));
            params.put("afterId", after.eventId());
        }

        return jdbc.sql(SQL.formatted(showFilters, eventFilters))
                .params(params)
                .query(ROW_MAPPER)
                .list();
    }

    /** The Postgres JDBC driver binds OffsetDateTime, not Instant. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}