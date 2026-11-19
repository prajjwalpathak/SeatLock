package com.seatlock.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import testsupport.IntegrationTest;

/** GET + cancel of bookings over HTTP (real stack). Cleans up bookings and seat statuses after each test. */
@IntegrationTest
class BookingApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;

    private final List<Long> userIds = new ArrayList<>();
    private String alice;
    private String bob;
    private long showId;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        alice = token(newUser("alice"));
        bob = token(newUser("bob"));
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id LIMIT 1
                """, Long.class);
        seats = jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 2
                """, Long.class, showId);
    }

    @AfterEach
    void cleanUp() {
        String ids = userIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        jdbc.update("DELETE FROM booking_seats WHERE booking_id IN (SELECT id FROM bookings WHERE user_id IN (%s))".formatted(ids));
        jdbc.update("DELETE FROM bookings WHERE user_id IN (%s)".formatted(ids));
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ANY (?)",
                showId, seats.toArray(Long[]::new));
        Set<String> keys = redis.keys("hold:*"); // test-only
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void ownerCanReadTheirBookingOthersCannot() throws Exception {
        String publicId = book(alice, seats.get(0), seats.get(1));

        mvc.perform(get("/api/v1/bookings/{id}", publicId).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.publicId").value(publicId))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.seats[0].section").exists());
        mvc.perform(get("/api/v1/bookings/{id}", publicId).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/{id}", "not-a-uuid").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingFreesTheSeatsAtOnceAndIsSafeToRepeat() throws Exception {
        String publicId = book(alice, seats.get(0));

        cancel(alice, publicId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());
        assertThat(jdbc.queryForObject("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?",
                String.class, showId, seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id
                WHERE b.public_id = ?::uuid AND bs.active
                """, Integer.class, publicId)).isZero();

        cancel(alice, publicId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        book(bob, seats.get(0)); // the seat can be held and booked again right away
    }

    @Test
    void aPaidOrExpiredBookingCannotBeCancelled() throws Exception {
        String paid = book(alice, seats.get(0));
        jdbc.update("UPDATE bookings SET status = 'CONFIRMED', confirmed_at = now() WHERE public_id = ?::uuid", paid);
        String expired = book(bob, seats.get(1));
        jdbc.update("UPDATE bookings SET status = 'EXPIRED' WHERE public_id = ?::uuid", expired);

        cancel(alice, paid)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_BOOKING_TRANSITION"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.action").value("cancel"));
        cancel(bob, expired)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void someoneElsesBookingCannotBeCancelled() throws Exception {
        String publicId = book(alice, seats.get(0));

        cancel(bob, publicId).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE public_id = ?::uuid", String.class, publicId))
                .isEqualTo("HELD");
    }

    @Test
    void myBookingsListsOnlyMyBookingsNewestFirstWithShowDetails() throws Exception {
        String first = book(alice, seats.get(0));
        String second = book(alice, seats.get(1));   // the first hold became a booking, so a new hold is allowed

        mvc.perform(get("/api/v1/me/bookings").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].publicId").value(second))
                .andExpect(jsonPath("$.items[1].publicId").value(first))
                .andExpect(jsonPath("$.items[0].status").value("HELD"))
                .andExpect(jsonPath("$.items[0].showId").value(showId))
                .andExpect(jsonPath("$.items[0].eventTitle").isNotEmpty())
                .andExpect(jsonPath("$.items[0].showStartsAt").exists())
                .andExpect(jsonPath("$.items[0].venueName").isNotEmpty())
                .andExpect(jsonPath("$.items[0].seatCount").value(1));

        mvc.perform(get("/api/v1/me/bookings").param("size", "1").param("page", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].publicId").value(first))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get("/api/v1/me/bookings").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void myBookingsRejectsBadPagingAndAnonymousCallers() throws Exception {
        mvc.perform(get("/api/v1/me/bookings").param("size", "0").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/bookings").param("size", "51").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/bookings"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    /** hold → booking; returns the booking's publicId. */
    private String book(String token, Long... seatIds) throws Exception {
        String hold = mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":" + List.of(seatIds) + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String booking = mvc.perform(post("/api/v1/holds/{id}/booking", (String) JsonPath.read(hold, "$.holdId"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(booking, "$.publicId");
    }

    private ResultActions cancel(String token, String publicId) throws Exception {
        return mvc.perform(post("/api/v1/bookings/{id}/cancel", publicId).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private User newUser(String name) {
        User user = users.save(new User(name + "-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}unused", name, Role.USER, AuthProvider.LOCAL));
        userIds.add(user.getId());
        return user;
    }

    private String token(User user) {
        return tokenService.issueAccessToken(
                new AuthenticatedUser(user.getId(), user.getEmail(), Role.USER, "unused")).value();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
