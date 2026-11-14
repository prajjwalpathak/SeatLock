package com.seatlock.catalog.application;

import com.seatlock.catalog.api.OrganizerEventResponse;
import com.seatlock.catalog.domain.EventCategory;
import com.seatlock.catalog.domain.EventStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read model for the organizer's dashboard: one query, show counts aggregated in SQL. */
@Repository
public class OrganizerEventQueryRepository {

    private static final String SQL = """
            SELECT e.id, e.title, e.category, e.language, e.status,
                   count(s.id) AS show_count,
                   count(s.id) FILTER (WHERE s.status = 'SCHEDULED' AND s.starts_at >= :now) AS upcoming_count,
                   min(s.starts_at) FILTER (WHERE s.status = 'SCHEDULED' AND s.starts_at >= :now) AS next_show_at
            FROM events e
            LEFT JOIN shows s ON s.event_id = e.id
            WHERE e.organizer_id = :organizerId
            GROUP BY e.id
            ORDER BY e.id DESC
            """;

    private static final RowMapper<OrganizerEventResponse> ROW = (rs, rowNum) -> {
        OffsetDateTime next = rs.getObject("next_show_at", OffsetDateTime.class);
        return new OrganizerEventResponse(
                rs.getLong("id"),
                rs.getString("title"),
                EventCategory.valueOf(rs.getString("category")),
                rs.getString("language"),
                EventStatus.valueOf(rs.getString("status")),
                rs.getInt("show_count"),
                rs.getInt("upcoming_count"),
                next == null ? null : next.toInstant());
    };

    private final JdbcClient jdbc;

    public OrganizerEventQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrganizerEventResponse> eventsOf(long organizerId, Instant now) {
        return jdbc.sql(SQL)
                .param("organizerId", organizerId)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(ROW)
                .list();
    }
}
