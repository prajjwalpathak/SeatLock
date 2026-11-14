package com.seatlock.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for creating a show's seats in one statement (no per-seat inserts). */
@Repository
public class ShowCreationRepository {

    private static final String SECTIONS_SQL = """
            SELECT DISTINCT section FROM seats WHERE venue_id = :venueId ORDER BY section
            """;

    // One INSERT ... SELECT for all ~250 seats: each seat gets its section's price.
    // The (section, price) pairs are a VALUES list of bound parameters; only placeholder names
    // (:s0, :p0, ...) are generated, never values, so there's nothing to inject.
    private static final String INSERT_SHOW_SEATS_SQL = """
            INSERT INTO show_seats (show_id, seat_id, price_paise)
            SELECT :showId, st.id, p.price_paise
            FROM seats st
            JOIN (VALUES %s) AS p(section, price_paise) ON p.section = st.section
            WHERE st.venue_id = :venueId
            """;


    private final JdbcClient jdbc;

    public ShowCreationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> sectionsOf(long venueId) {
        return jdbc.sql(SECTIONS_SQL).param("venueId", venueId).query(String.class).list();
    }

    /** @return number of show_seats rows created */
    public int insertShowSeats(long showId, long venueId, Map<String, Long> pricePaiseBySection) {
        List<Map.Entry<String, Long>> pairs = List.copyOf(pricePaiseBySection.entrySet());
        StringJoiner values = new StringJoiner(", ");
        for (int i = 0; i < pairs.size(); i++) {
            values.add("(CAST(:s" + i + " AS varchar), CAST(:p" + i + " AS bigint))");
        }
        var statement = jdbc.sql(INSERT_SHOW_SEATS_SQL.formatted(values))
                .param("showId", showId)
                .param("venueId", venueId);
        for (int i = 0; i < pairs.size(); i++) {
            statement = statement.param("s" + i, pairs.get(i).getKey()).param("p" + i, pairs.get(i).getValue());
        }
        return statement.update();
    }
}
