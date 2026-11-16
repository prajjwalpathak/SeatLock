package com.seatlock.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/** The per-user limit on POST /holds over HTTP (test profile: 20 per minute, burst 20). */
@IntegrationTest
class HoldRateLimitIntegrationTest {

    private static final int USER_CAPACITY = 20; // application-test.yml

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;

    @AfterEach
    void removeHoldKeys() {
        Set<String> keys = redis.keys("hold:*"); // test-only
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void theUserIsLimitedAfterTheBurstOthersAreNot() throws Exception {
        String alice = token("alice");
        String bob = token("bob");
        long showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id LIMIT 1
                """, Long.class);
        long seat = jdbc.queryForObject(
                "SELECT min(seat_id) FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE'", Long.class, showId);

        for (int i = 0; i < USER_CAPACITY; i++) {
            MockHttpServletResponse r = hold(alice, showId, seat);
            assertThat(r.getStatus()).as("request %d: %s", i + 1, r.getContentAsString()).isNotEqualTo(429);
            assertThat(r.getHeader("RateLimit-Remaining")).isEqualTo(String.valueOf(USER_CAPACITY - 1 - i));
        }

        mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seat + "]}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        assertThat(hold(bob, showId, seat).getStatus()).isNotEqualTo(429);   // per user, not global
    }

    private MockHttpServletResponse hold(String token, long showId, long seat) throws Exception {
        return mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seat + "]}"))
                .andReturn().getResponse();
    }

    private String token(String name) {
        User user = users.save(new User(name + "-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}unused", name, Role.USER, AuthProvider.LOCAL));
        return tokenService.issueAccessToken(
                new AuthenticatedUser(user.getId(), user.getEmail(), Role.USER, "unused")).value();
    }
}
