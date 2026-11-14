package com.seatlock.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.catalog.application.OrganizerCatalogService;
import com.seatlock.common.exception.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import testsupport.IntegrationTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creating shows against the real schema. Each test uses its own far-future day, so created shows
 * never collide with seed data or with each other (the DB is shared by all integration tests).
 */
@IntegrationTest
class CreateShowIntegrationTest {

    static final Instant BASE = Instant.now().plus(Duration.ofDays(200)).truncatedTo(ChronoUnit.DAYS);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired OrganizerCatalogService organizerService;

    long organizerId;
    long eventId;
    int durationMinutes;
    long venueId;
    Map<String, Long> prices;

    @BeforeEach
    void pickTheCinemaOrganizersEventAndAVenue() {
        organizerId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'organizer.cinema@seatlock.test'", Long.class);
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT id, duration_minutes FROM events WHERE organizer_id = ? AND status = 'PUBLISHED' ORDER BY id LIMIT 1",
                organizerId);
        eventId = ((Number) event.get("id")).longValue();
        durationMinutes = ((Number) event.get("duration_minutes")).intValue();
        venueId = jdbc.queryForObject("SELECT id FROM venues ORDER BY id LIMIT 1", Long.class);
        prices = new LinkedHashMap<>();
        long price = 150_000;
        for (String section : jdbc.queryForList(
                "SELECT DISTINCT section FROM seats WHERE venue_id = ? ORDER BY section", String.class, venueId)) {
            prices.put(section, price);
            price -= 30_000;
        }
    }

    @Test
    void createsShowAndPricesEverySeatBySectionInOneGo() throws Exception {
        Instant startsAt = BASE.plus(Duration.ofDays(1)).plus(Duration.ofHours(14));

        String body = create(eventId, venueId, startsAt, prices)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/v1/shows/\\d+/seats")))
                .andExpect(jsonPath("$.endsAt").value(startsAt.plus(Duration.ofMinutes(durationMinutes + 30)).toString()))
                .andReturn().getResponse().getContentAsString();
        long showId = ((Number) JsonPath.read(body, "$.showId")).longValue();

        int venueSeats = jdbc.queryForObject("SELECT count(*) FROM seats WHERE venue_id = ?", Integer.class, venueId);
        assertThat(((Number) JsonPath.read(body, "$.seatCount")).intValue()).isEqualTo(venueSeats);
        List<Map<String, Object>> perSection = jdbc.queryForList("""
                SELECT st.section, min(ss.price_paise) AS lo, max(ss.price_paise) AS hi
                FROM show_seats ss JOIN seats st ON st.id = ss.seat_id
                WHERE ss.show_id = ? GROUP BY st.section""", showId);
        assertThat(perSection).allSatisfy(row -> {
            long expected = prices.get((String) row.get("section"));
            assertThat(((Number) row.get("lo")).longValue()).isEqualTo(expected);
            assertThat(((Number) row.get("hi")).longValue()).isEqualTo(expected);
        });
    }

    @Test
    void overlappingShowAtTheSameVenueIs409ButBackToBackIsFine() throws Exception {
        Instant first = BASE.plus(Duration.ofDays(2)).plus(Duration.ofHours(10));
        Instant firstEnds = first.plus(Duration.ofMinutes(durationMinutes + 30));
        create(eventId, venueId, first, prices).andExpect(status().isCreated());

        create(eventId, venueId, first.plus(Duration.ofMinutes(30)), prices)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOW_OVERLAP"));
        create(eventId, venueId, firstEnds, prices).andExpect(status().isCreated()); // [start, end) is half-open
    }

    @Test
    void twoOrganizersRacingForTheSameSlotOneWinsTheDatabaseDecides() throws Exception {
        Instant slot = BASE.plus(Duration.ofDays(3)).plus(Duration.ofHours(12));
        var request = new CreateShowRequest(venueId, slot, prices);
        CountDownLatch startGate = new CountDownLatch(1);
        int winners = 0;
        int conflicts = 0;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> attempts = List.of(
                    pool.submit(() -> { startGate.await(); return tryCreate(request); }),
                    pool.submit(() -> { startGate.await(); return tryCreate(new CreateShowRequest(venueId, slot.plus(Duration.ofMinutes(10)), prices)); }));
            startGate.countDown();
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) winners++; else conflicts++;
            }
        }
        assertThat(winners).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
    }

    @Test
    void someoneElsesEventIsNotFound() throws Exception {
        long otherEvent = jdbc.queryForObject(
                "SELECT id FROM events WHERE organizer_id <> ? ORDER BY id LIMIT 1", Long.class, organizerId);
        create(otherEvent, venueId, BASE.plus(Duration.ofDays(4)), prices)
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestsAre400WithAReason() throws Exception {
        Instant ok = BASE.plus(Duration.ofDays(5));
        Map<String, Long> missingSection = new LinkedHashMap<>(prices);
        missingSection.remove(missingSection.keySet().iterator().next());
        Map<String, Long> extraSection = new LinkedHashMap<>(prices);
        extraSection.put("BALCONY", 10_000L);
        Map<String, Long> zeroPrice = new LinkedHashMap<>(prices);
        zeroPrice.replaceAll((section, price) -> 0L);

        create(eventId, venueId, ok, missingSection).andExpect(jsonPath("$.code").value("SECTION_PRICES_MISMATCH"));
        create(eventId, venueId, ok, extraSection).andExpect(jsonPath("$.code").value("SECTION_PRICES_MISMATCH"));
        create(eventId, venueId, ok, zeroPrice).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(eventId, 9_999_999L, ok, prices).andExpect(jsonPath("$.code").value("UNKNOWN_VENUE"));
        create(eventId, venueId, Instant.now().plus(Duration.ofMinutes(10)), prices)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHOW_TOO_SOON"));
    }

    private boolean tryCreate(CreateShowRequest request) {
        try {
            organizerService.createShow(organizerId, eventId, request);
            return true;
        } catch (ConflictException ex) {
            assertThat(ex.getCode()).isEqualTo("SHOW_OVERLAP");
            return false;
        }
    }

    private ResultActions create(long event, long venue, Instant startsAt, Map<String, Long> sectionPrices) throws Exception {
        String body = json.writeValueAsString(new CreateShowRequest(venue, startsAt, sectionPrices));
        return mvc.perform(post("/api/v1/organizer/events/{id}/shows", event)
                .with(organizer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private RequestPostProcessor organizer() {
        return jwt().jwt(j -> j.subject(Long.toString(organizerId))).authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER"));
    }
}
