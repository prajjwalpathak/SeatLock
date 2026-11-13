package com.seatlock.catalog.application;

import com.seatlock.catalog.api.ShowSeatMapResponse;
import com.seatlock.catalog.api.ShowSummaryResponse;
import com.seatlock.catalog.domain.ShowSeatStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Read-model queries about shows (one SQL round trip per call, no N+1). */
@Repository
public class ShowQueryRepository {

    private static final String UPCOMING_SHOWS_SQL = """
            SELECT s.id, s.starts_at, v.id AS venue_id, v.name AS venue_name, v.city,
                   (SELECT min(ss.price_paise) FROM show_seats ss WHERE ss.show_id = s.id) AS from_price_paise
            FROM shows s
            JOIN venues v ON v.id = s.venue_id
            WHERE s.event_id = :eventId
              AND s.status = 'SCHEDULED'
              AND s.starts_at >= :from
            ORDER BY s.starts_at, s.id
            """;

    private static final RowMapper<ShowSummaryResponse> SHOW_SUMMARY = (rs, rowNum) -> new ShowSummaryResponse(
            rs.getLong("id"),
            rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
            rs.getLong("venue_id"),
            rs.getString("venue_name"),
            rs.getString("city"),
            rs.getLong("from_price_paise"));

    private final JdbcClient jdbc;

    public ShowQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ShowSummaryResponse> upcomingShows(long eventId, Instant from) {
        return jdbc.sql(UPCOMING_SHOWS_SQL)
                .param("eventId", eventId)
                .param("from", from.atOffset(ZoneOffset.UTC)) // driver binds OffsetDateTime, not Instant
                .query(SHOW_SUMMARY)
                .list();
    }


    // ------------------------------------------------------------------ seat map

    /** Show + event + venue fields needed for the seat-map header. */
    public record ShowHeader(long showId, Instant startsAt, long eventId, String eventTitle,
                             long venueId, String venueName, String city) {}

    private static final String BOOKABLE_SHOW_SQL = """
            SELECT s.id, s.starts_at, e.id AS event_id, e.title,
                   v.id AS venue_id, v.name AS venue_name, v.city
            FROM shows s
            JOIN events e ON e.id = s.event_id
            JOIN venues v ON v.id = s.venue_id
            WHERE s.id = :showId
              AND s.status = 'SCHEDULED'
              AND e.status = 'PUBLISHED'
            """;

    private static final String SEATS_SQL = """
            SELECT st.id AS seat_id, st.section, st.row_label, st.seat_number,
                   st.grid_row, st.grid_col, st.is_aisle, ss.price_paise, ss.status
            FROM show_seats ss
            JOIN seats st ON st.id = ss.seat_id
            WHERE ss.show_id = :showId
            ORDER BY st.grid_row, st.grid_col
            """;

    /** The show, if it can be booked: SCHEDULED and its event PUBLISHED. */
    public Optional<ShowHeader> findBookableShow(long showId) {
        return jdbc.sql(BOOKABLE_SHOW_SQL)
                .param("showId", showId)
                .query((rs, rowNum) -> new ShowHeader(
                        rs.getLong("id"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("event_id"),
                        rs.getString("title"),
                        rs.getLong("venue_id"),
                        rs.getString("venue_name"),
                        rs.getString("city")))
                .optional();
    }

    /** Every seat of the show in rendering order (row by row, left to right). */
    public List<ShowSeatMapResponse.SeatView> seatsForShow(long showId) {
        return jdbc.sql(SEATS_SQL)
                .param("showId", showId)
                .query((rs, rowNum) -> new ShowSeatMapResponse.SeatView(
                        rs.getLong("seat_id"),
                        rs.getString("section"),
                        rs.getString("row_label"),
                        rs.getInt("seat_number"),
                        rs.getInt("grid_row"),
                        rs.getInt("grid_col"),
                        rs.getBoolean("is_aisle"),
                        rs.getLong("price_paise"),
                        ShowSeatStatus.valueOf(rs.getString("status"))))
                .list();
    }
}