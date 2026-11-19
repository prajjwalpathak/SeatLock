package com.seatlock.booking.application;

import com.seatlock.booking.domain.BookingStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read model for "My bookings" (JdbcClient like the catalog reads, #23): one page query with the seat count as a
 * correlated subquery, plus one count. Avoids loading entities with their seat collections just to count them
 * (and Hibernate's in-memory pagination of collection fetches).
 */
@Repository
class MyBookingsQuery {

    /** A booking row before its show labels are joined in from the catalog. */
    record Row(UUID publicId, BookingStatus status, long showId, int seatCount, long totalPaise, String currency,
               java.time.Instant expiresAt, java.time.Instant createdAt) {}

    private final JdbcClient jdbc;

    MyBookingsQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first; ties (same millisecond) by id, so pages never overlap or skip. */
    List<Row> page(long userId, int page, int size) {
        return jdbc.sql("""
                        SELECT b.public_id, b.status, b.show_id, b.total_paise, b.currency, b.expires_at, b.created_at,
                               (SELECT count(*) FROM booking_seats bs WHERE bs.booking_id = b.id) AS seat_count
                        FROM bookings b
                        WHERE b.user_id = :userId
                        ORDER BY b.created_at DESC, b.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("userId", userId)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, rowNum) -> new Row(
                        rs.getObject("public_id", UUID.class),
                        BookingStatus.valueOf(rs.getString("status")),
                        rs.getLong("show_id"),
                        rs.getInt("seat_count"),
                        rs.getLong("total_paise"),
                        rs.getString("currency"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    long count(long userId) {
        return jdbc.sql("SELECT count(*) FROM bookings WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
