package com.seatlock.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end catalog API checks against the seeded database (Testcontainers from W1·D5).
 * Expectations are computed from the DB, because seed show dates are relative to when V2 ran.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void pagingReturnsEveryPublishedUpcomingEventExactlyOnceInOrder() throws Exception {
        List<Object> ids = new ArrayList<>();
        List<Instant> nextShows = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder req = get("/api/v1/events").param("limit", "7");
            if (cursor != null) {
                req = req.param("cursor", cursor);
            }
            String body = mvc.perform(req).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            ids.addAll(JsonPath.<List<Object>>read(body, "$.items[*].id"));
            JsonPath.<List<String>>read(body, "$.items[*].nextShowAt").forEach(t -> nextShows.add(Instant.parse(t)));
            cursor = JsonPath.read(body, "$.nextCursor");
            pages++;
        } while (cursor != null && pages < 50);

        Long expected = jdbc.queryForObject("""
                SELECT count(DISTINCT e.id) FROM events e JOIN shows s ON s.event_id = e.id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at >= now()
                """, Long.class);

        assertThat(ids).doesNotHaveDuplicates().hasSize(expected.intValue());
        assertThat(nextShows).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void cityFilterOnlyReturnsShowsInThatCity() throws Exception {
        mvc.perform(get("/api/v1/events").param("city", "mumbai").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", not(empty())))
                .andExpect(jsonPath("$.items[*].nextShowCity", everyItem(equalToIgnoringCase("Mumbai"))));
    }

    @Test
    void draftEventIsNotFound() throws Exception {
        Long draftId = jdbc.queryForObject("SELECT id FROM events WHERE status = 'DRAFT' LIMIT 1", Long.class);
        mvc.perform(get("/api/v1/events/{id}", draftId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void seatMapContainsEverySeatWithSectionsMostExpensiveFirst() throws Exception {
        Long showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' ORDER BY s.id LIMIT 1
                """, Long.class);
        Integer seatCount = jdbc.queryForObject(
                "SELECT count(*) FROM show_seats WHERE show_id = ?", Integer.class, showId);
        Integer gridCols = jdbc.queryForObject("""
                SELECT max(st.grid_col) + 1 FROM show_seats ss JOIN seats st ON st.id = ss.seat_id
                WHERE ss.show_id = ?
                """, Integer.class, showId);

        String body = mvc.perform(get("/api/v1/shows/{id}/seats", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showId").value(showId))
                .andExpect(jsonPath("$.seats.length()").value(seatCount))
                .andExpect(jsonPath("$.gridCols").value(gridCols))
                .andReturn().getResponse().getContentAsString();

        List<Number> sectionPrices = JsonPath.read(body, "$.sections[*].pricePaise");
        assertThat(sectionPrices.stream().map(Number::longValue).toList())
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void badInputsAreClientErrorsNotServerErrors() throws Exception {
        mvc.perform(get("/api/v1/events").param("cursor", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(get("/api/v1/events").param("category", "OPERA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}