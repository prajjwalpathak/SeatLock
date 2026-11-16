package com.seatlock.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Idempotency-Key on POST /holds through the real stack: security → interceptor → HoldService → Lua. */
@IntegrationTest
class HoldIdempotencyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;

    private long aliceId;
    private String alice;
    private String bob;
    private long showId;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        User a = newUser("alice");
        aliceId = a.getId();
        alice = token(a);
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
    void removeHoldKeys() {
        Set<String> keys = redis.keys("hold:*"); // test-only
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void aRetryWithTheSameKeyGetsTheSameHoldBack() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = hold(alice, key, seats.get(0))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        String retry = hold(alice, key, seats.get(0))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(retry, "$.holdId")).isEqualTo(JsonPath.<String>read(first, "$.holdId"));
        for (String field : List.of("$.totalPaise", "$.expiresAt", "$.seats.length()")) {
            assertThat(JsonPath.<Object>read(retry, field)).as(field).isEqualTo(JsonPath.read(first, field));
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM idempotency_keys WHERE user_id = ? AND idem_key = ?", String.class, aliceId, key))
                .isEqualTo("COMPLETED");
    }

    @Test
    void theSameKeyForDifferentSeatsIs422AndHoldsNothing() throws Exception {
        String key = UUID.randomUUID().toString();
        hold(alice, key, seats.get(0)).andExpect(status().isCreated());

        hold(alice, key, seats.get(1))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(redis.hasKey(HoldKeys.seatKey(showId, seats.get(1)))).isFalse();
    }

    @Test
    void aMissingKeyIs400AndHoldsNothing() throws Exception {
        mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON).content(body(seats.get(0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(redis.hasKey(HoldKeys.seatKey(showId, seats.get(0)))).isFalse();
    }

    @Test
    void aFailedAttemptCanBeRetriedWithTheSameKey() throws Exception {
        String bobsHold = JsonPath.read(hold(bob, UUID.randomUUID().toString(), seats.get(0))
                .andReturn().getResponse().getContentAsString(), "$.holdId");
        String key = UUID.randomUUID().toString();

        hold(alice, key, seats.get(0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"));     // failure: key released, not stored
        mvc.perform(delete("/api/v1/holds/{id}", bobsHold).header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNoContent());

        hold(alice, key, seats.get(0))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));      // really ran again
    }

    @Test
    void aSecondHoldAttemptWithANewKeyGetsHoldExistsWithTheHoldId() throws Exception {
        String holdId = JsonPath.read(hold(alice, UUID.randomUUID().toString(), seats.get(0))
                .andReturn().getResponse().getContentAsString(), "$.holdId");

        hold(alice, UUID.randomUUID().toString(), seats.get(1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXISTS"))
                .andExpect(jsonPath("$.holdId").value(holdId));                // client can find its hold
    }

    /**
     * Ten copies of the same request (same user, key and body) at once: exactly one hold is created;
     * every other copy either replays it (201, same holdId) or is told the first is still running (409).
     */
    @Test
    void concurrentDuplicatesCreateExactlyOneHold() throws Exception {
        String key = UUID.randomUUID().toString();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return hold(alice, key, seats.get(0)).andReturn().getResponse();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Set<String> holdIds = new java.util.HashSet<>();
            int created = 0;
            for (Future<MockHttpServletResponse> f : futures) {
                MockHttpServletResponse r = f.get(30, TimeUnit.SECONDS);
                String json = r.getContentAsString();
                if (r.getStatus() == 201) {
                    created++;
                    holdIds.add(JsonPath.read(json, "$.holdId"));
                } else {
                    assertThat(r.getStatus()).as(json).isEqualTo(409);
                    assertThat(JsonPath.<String>read(json, "$.code")).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                }
            }
            assertThat(created).isGreaterThanOrEqualTo(1);
            assertThat(holdIds).hasSize(1);                                      // never two different holds
            assertThat(redis.keys("hold:id:*")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions hold(String token, String key, long seatId) throws Exception {
        return mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body(seatId)));
    }

    private static String body(long seatId) {
        return "{\"seatIds\":[" + seatId + "]}";
    }

    private User newUser(String name) {
        return users.save(new User(name + "-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}unused", name, Role.USER, AuthProvider.LOCAL));
    }

    private String token(User user) {
        return tokenService.issueAccessToken(
                new AuthenticatedUser(user.getId(), user.getEmail(), Role.USER, "unused")).value();
    }
}
