package com.seatlock.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.booking.hold.HoldKeys;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/** Manual Seat Finder over HTTP on the seeded hall; every claim is checked against the database. */
@IntegrationTest
class SeatFinderApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    private long showId;
    private final List<String> holdKeys = new ArrayList<>();

    @BeforeEach
    void pickShow() {
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 day'
                ORDER BY s.id LIMIT 1
                """, Long.class);
    }

    @AfterEach
    void removeHoldKeys() {
        holdKeys.forEach(redis::delete);
    }

    private String find(String body) throws Exception {
        return mvc.perform(post("/api/v1/shows/{id}/seat-finder", showId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
    }

    private List<List<Number>> seatIdsPerOption(String json) {
        return JsonPath.read(json, "$.options[*].seatIds");
    }

    private Map<String, Object> seatRow(Number seatId) {
        return jdbc.queryForMap("""
                SELECT st.grid_row, st.grid_col, st.is_aisle, ss.price_paise, ss.status, st.section
                FROM show_seats ss JOIN seats st ON st.id = ss.seat_id
                WHERE ss.show_id = ? AND ss.seat_id = ?""", showId, seatId.longValue());
    }

    @Test
    void anonymousGetsUpToThreeGroupsOfAdjacentAvailableSeats() throws Exception {
        List<List<Number>> options = seatIdsPerOption(find("{\"count\":3}"));

        assertThat(options).hasSizeBetween(1, 3);
        List<Number> all = new ArrayList<>();
        for (List<Number> ids : options) {
            assertThat(ids).hasSize(3);
            List<Map<String, Object>> rows = ids.stream().map(this::seatRow).toList();
            assertThat(rows).extracting(r -> r.get("grid_row")).containsOnly(rows.getFirst().get("grid_row"));
            List<Integer> cols = rows.stream().map(r -> (Integer) r.get("grid_col")).toList();
            assertThat(cols.get(1)).isEqualTo(cols.get(0) + 1);   // side by side, no aisle in between
            assertThat(cols.get(2)).isEqualTo(cols.get(1) + 1);
            assertThat(rows).extracting(r -> r.get("status")).containsOnly("AVAILABLE");
            all.addAll(ids);
        }
        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    void filtersAreHardLimits() throws Exception {
        long cheapest = jdbc.queryForObject("SELECT min(price_paise) FROM show_seats WHERE show_id = ?", Long.class, showId);
        String json = find("{\"count\":2,\"maxPricePaise\":" + cheapest + ",\"aisle\":true,\"rows\":\"BACK\"}");

        List<List<Number>> options = seatIdsPerOption(json);
        assertThat(options).isNotEmpty();
        for (List<Number> ids : options) {
            List<Map<String, Object>> rows = ids.stream().map(this::seatRow).toList();
            assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("price_paise")).longValue()).isLessThanOrEqualTo(cheapest));
            assertThat(rows).anySatisfy(r -> assertThat(r.get("is_aisle")).isEqualTo(true));
        }
        assertThat((List<Boolean>) JsonPath.read(json, "$.options[*].hasAisleSeat")).containsOnly(true);
    }

    @Test
    void heldSeatsAreNeverSuggested() throws Exception {
        List<Number> best = seatIdsPerOption(find("{\"count\":2}")).getFirst();
        for (Number id : best) {
            String key = HoldKeys.seatKey(showId, id.longValue());
            redis.opsForValue().set(key, "someone-elses-hold", Duration.ofMinutes(1));
            holdKeys.add(key);
        }

        List<List<Number>> after = seatIdsPerOption(find("{\"count\":2}"));
        assertThat(after).allSatisfy(ids -> assertThat(ids).doesNotContainAnyElementsOf(best));
    }

    @Test
    void sectionFilterAndUnknownSection() throws Exception {
        String json = find("{\"count\":2,\"sections\":[\"GOLD\"]}");
        assertThat((List<String>) JsonPath.read(json, "$.options[*].sections[*]")).containsOnly("GOLD");

        mvc.perform(post("/api/v1/shows/{id}/seat-finder", showId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":2,\"sections\":[\"BALCONY\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SECTION"))
                .andExpect(jsonPath("$.sections").isArray());
    }

    @Test
    void invalidRequests() throws Exception {
        for (String body : List.of("{\"count\":0}", "{}", "{\"count\":2,\"maxPricePaise\":-1}")) {
            mvc.perform(post("/api/v1/shows/{id}/seat-finder", showId).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/shows/{id}/seat-finder", showId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_SEATS"));
        mvc.perform(post("/api/v1/shows/{id}/seat-finder", 999_999).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":2}"))
                .andExpect(status().isNotFound());
    }
}
