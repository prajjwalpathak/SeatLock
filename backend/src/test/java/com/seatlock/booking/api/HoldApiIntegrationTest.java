package com.seatlock.booking.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import testsupport.IntegrationTest;

/** Holds over HTTP: real users and tokens, real Postgres, real Redis + Lua. */
@IntegrationTest
class HoldApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;

    private String alice;
    private String bob;
    private long showId;
    private List<Long> seats; // 7 AVAILABLE seats of showId

    @BeforeEach
    void setUp() {
        alice = tokenFor("alice");
        bob = tokenFor("bob");
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now()
                ORDER BY s.id LIMIT 1
                """, Long.class);
        seats = jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 7
                """, Long.class, showId);
    }

    @AfterEach
    void removeHoldKeys() {
        Set<String> keys = redis.keys("hold:*"); // test-only; production code never uses KEYS
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void holdShowsUpOnTheSeatMapAndReleaseFreesIt() throws Exception {
        long expectedTotal = jdbc.queryForObject(
                "SELECT sum(price_paise) FROM show_seats WHERE show_id = ? AND seat_id IN (?, ?)",
                Long.class, showId, seats.get(0), seats.get(1));

        String body = hold(alice, seats.get(0), seats.get(1))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/holds/")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.showId").value(showId))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.totalPaise").value(expectedTotal))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        String holdId = JsonPath.read(body, "$.holdId");

        seatStatus(seats.get(0)).andExpect(jsonPath(seatPath(seats.get(0))).value("HELD"));
        mvc.perform(get("/api/v1/holds/{id}", holdId).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId));

        mvc.perform(delete("/api/v1/holds/{id}", holdId).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isNoContent());

        seatStatus(seats.get(0)).andExpect(jsonPath(seatPath(seats.get(0))).value("AVAILABLE"));
        mvc.perform(get("/api/v1/holds/{id}", holdId).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    void secondUserGetsTheTakenSeatsBack() throws Exception {
        hold(alice, seats.get(0), seats.get(1)).andExpect(status().isCreated());

        hold(bob, seats.get(1), seats.get(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"))
                .andExpect(jsonPath("$.seatIds", hasSize(1)))
                .andExpect(jsonPath("$.seatIds[0]").value(seats.get(1)));
        seatStatus(seats.get(2)).andExpect(jsonPath(seatPath(seats.get(2))).value("AVAILABLE")); // all or nothing
    }

    @Test
    void oneHoldPerUserPerShow() throws Exception {
        hold(alice, seats.get(0)).andExpect(status().isCreated());

        hold(alice, seats.get(3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXISTS"));
    }

    @Test
    void someoneElsesHoldIsNotFound() throws Exception {
        String body = hold(alice, seats.get(0)).andReturn().getResponse().getContentAsString();
        String holdId = JsonPath.read(body, "$.holdId");

        mvc.perform(get("/api/v1/holds/{id}", holdId).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/holds/{id}", holdId).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());
        seatStatus(seats.get(0)).andExpect(jsonPath(seatPath(seats.get(0))).value("HELD")); // still alice's
    }

    @Test
    void badRequestsGetSpecificCodes() throws Exception {
        hold(alice, seats.toArray(Long[]::new))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_SEATS"));
        hold(alice, seats.get(0), seats.get(0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_SEATS"));

        long seatOfAnotherVenue = jdbc.queryForObject("""
                SELECT min(st.id) FROM seats st
                WHERE st.venue_id <> (SELECT venue_id FROM shows WHERE id = ?)
                """, Long.class, showId);
        hold(alice, seatOfAnotherVenue)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_SEATS"));

        mvc.perform(post("/api/v1/shows/{id}/holds", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1]}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/shows/{id}/holds", showId).header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/holds/{id}", "not-a-uuid").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional // the BOOKED update is rolled back after the test
    void aSeatBookedInTheDatabaseCannotBeHeld() throws Exception {
        jdbc.update("UPDATE show_seats SET status = 'BOOKED' WHERE show_id = ? AND seat_id = ?", showId, seats.get(0));

        hold(alice, seats.get(0), seats.get(1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"))
                .andExpect(jsonPath("$.seatIds[0]").value(seats.get(0)));
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions hold(String token, Long... seatIds) throws Exception {
        String json = "{\"seatIds\":[" + List.of(seatIds).stream().map(String::valueOf).collect(Collectors.joining(",")) + "]}";
        return mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header("Idempotency-Key", UUID.randomUUID().toString()) // a fresh attempt every call
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions seatStatus(long seatId) throws Exception {
        return mvc.perform(get("/api/v1/shows/{id}/seats", showId)).andExpect(status().isOk());
    }

    private static String seatPath(long seatId) {
        return "$.seats[?(@.seatId == %d)].status".formatted(seatId);
    }

    private String tokenFor(String name) {
        User user = users.save(new User(name + "-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}unused", name, Role.USER, AuthProvider.LOCAL));
        return tokenService.issueAccessToken(
                new AuthenticatedUser(user.getId(), user.getEmail(), Role.USER, "unused")).value();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
