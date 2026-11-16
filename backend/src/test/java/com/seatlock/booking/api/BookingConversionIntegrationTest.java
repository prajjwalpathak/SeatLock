package com.seatlock.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.booking.hold.HoldKeys;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import testsupport.IntegrationTest;

/** Hold → booking over HTTP with real Postgres + Redis. Cleans up its bookings and seat statuses after each test. */
@IntegrationTest
class BookingConversionIntegrationTest {

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
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 3
                """, Long.class, showId);
    }

    @AfterEach
    void cleanUp() {
        String users = userIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        jdbc.update("""
                UPDATE show_seats ss SET status = 'AVAILABLE'
                FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id
                WHERE b.user_id IN (%s) AND ss.show_id = bs.show_id AND ss.seat_id = bs.seat_id
                """.formatted(users));
        jdbc.update("DELETE FROM booking_seats WHERE booking_id IN (SELECT id FROM bookings WHERE user_id IN (%s))".formatted(users));
        jdbc.update("DELETE FROM bookings WHERE user_id IN (%s)".formatted(users));
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ANY (?)",
                showId, seats.toArray(Long[]::new));
        Set<String> keys = redis.keys("hold:*"); // test-only
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void aHoldBecomesABookingAndTheDatabaseTakesOver() throws Exception {
        String holdJson = holdBody(alice, seats.get(0), seats.get(1));
        String holdId = JsonPath.read(holdJson, "$.holdId");

        convert(alice, holdId)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/bookings/")))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.showId").value(showId))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.seats[0].rowLabel").exists())
                .andExpect(jsonPath("$.totalPaise").value(((Number) JsonPath.read(holdJson, "$.totalPaise")).longValue()))
                .andExpect(jsonPath("$.expiresAt").value((String) JsonPath.read(holdJson, "$.expiresAt")));

        // Postgres now holds the seats; the Redis hold is gone; the seat map still shows HELD (from the DB).
        assertThat(jdbc.queryForList("SELECT status FROM show_seats WHERE show_id = ? AND seat_id IN (?, ?)",
                String.class, showId, seats.get(0), seats.get(1))).containsOnly("HELD");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id
                WHERE b.hold_id = ?::uuid AND bs.active
                """, Integer.class, holdId)).isEqualTo(2);
        assertThat(redis.hasKey(HoldKeys.recordKey(holdId))).isFalse();
        assertThat(redis.hasKey(HoldKeys.seatKey(showId, seats.get(0)))).isFalse();
        mvc.perform(get("/api/v1/shows/{id}/seats", showId))
                .andExpect(jsonPath("$.seats[?(@.seatId == %d)].status".formatted(seats.get(0))).value("HELD"));
    }

    @Test
    void convertingTwiceReturnsTheSameBookingWith200() throws Exception {
        String holdId = JsonPath.read(holdBody(alice, seats.get(0)), "$.holdId");
        String first = convert(alice, holdId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String second = convert(alice, holdId)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(second, "$.publicId")).isEqualTo(JsonPath.read(first, "$.publicId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE hold_id = ?::uuid", Integer.class, holdId))
                .isEqualTo(1);
    }

    @Test
    void someoneElsesOrAnUnknownHoldIsNotFound() throws Exception {
        String holdId = JsonPath.read(holdBody(alice, seats.get(0)), "$.holdId");

        convert(bob, holdId).andExpect(status().isNotFound());
        convert(alice, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        convert(alice, "not-a-uuid").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE hold_id = ?::uuid", Integer.class, holdId))
                .isZero();
    }

    @Test
    void aSeatBookedInTheDatabaseMeanwhileFailsTheWholeConversion() throws Exception {
        String holdId = JsonPath.read(holdBody(alice, seats.get(0), seats.get(1)), "$.holdId");
        jdbc.update("UPDATE show_seats SET status = 'BOOKED' WHERE show_id = ? AND seat_id = ?", showId, seats.get(1));

        convert(alice, holdId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"));

        assertThat(jdbc.queryForObject("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?",
                String.class, showId, seats.get(0))).isEqualTo("AVAILABLE");     // rolled back, not half-held
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE hold_id = ?::uuid", Integer.class, holdId))
                .isZero();
    }

    /** The last line of defence: Redis "forgot" a seat that a live booking still has. The partial unique index wins. */
    @Test
    void theDatabaseIndexRejectsASeatAlreadyInALiveBooking() throws Exception {
        // bob's live booking claims seat 0 in booking_seats, but show_seats wasn't updated (inconsistent on purpose)
        long bobId = userIds.get(1);
        Long bookingId = jdbc.queryForObject("""
                INSERT INTO bookings (user_id, show_id, hold_id, status, total_paise, expires_at)
                VALUES (?, ?, gen_random_uuid(), 'HELD', 100, now() + interval '5 minutes') RETURNING id
                """, Long.class, bobId, showId);
        jdbc.update("INSERT INTO booking_seats (booking_id, show_id, seat_id, price_paise) VALUES (?, ?, ?, 100)",
                bookingId, showId, seats.get(0));
        String holdId = JsonPath.read(holdBody(alice, seats.get(0)), "$.holdId"); // Redis knows nothing about bob

        convert(alice, holdId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"))
                .andExpect(jsonPath("$.seatIds[0]").value(seats.get(0)));
        assertThat(jdbc.queryForObject("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?",
                String.class, showId, seats.get(0))).isEqualTo("AVAILABLE");     // markHeld rolled back too
    }

    @Test
    void concurrentConversionsOfOneHoldCreateExactlyOneBooking() throws Exception {
        String holdId = JsonPath.read(holdBody(alice, seats.get(0), seats.get(1)), "$.holdId");
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return convert(alice, holdId).andReturn().getResponse();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int created = 0;
            Set<String> publicIds = new HashSet<>();
            for (Future<MockHttpServletResponse> f : futures) {
                MockHttpServletResponse r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.getStatus()).as(r.getContentAsString()).isIn(200, 201);
                if (r.getStatus() == 201) {
                    created++;
                }
                publicIds.add(JsonPath.read(r.getContentAsString(), "$.publicId"));
            }
            assertThat(created).isEqualTo(1);
            assertThat(publicIds).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE hold_id = ?::uuid", Integer.class, holdId))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private String holdBody(String token, Long... seatIds) throws Exception {
        String json = "{\"seatIds\":" + List.of(seatIds) + "}";
        return mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions convert(String token, String holdId) throws Exception {
        return mvc.perform(post("/api/v1/holds/{id}/booking", holdId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
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
}
