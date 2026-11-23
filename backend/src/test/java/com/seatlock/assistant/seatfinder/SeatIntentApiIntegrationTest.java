package com.seatlock.assistant.seatfinder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.assistant.ai.AiGateway;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.time.Duration;
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
import testsupport.IntegrationTest;

/**
 * AI Seat Finder over HTTP with no model (test profile): a fallback when nothing is cached, and the full
 * text → intent → options path when a validated intent is already in the Redis cache.
 */
@IntegrationTest
class SeatIntentApiIntegrationTest {

    private static final String TEXT = "Two aisle seats, front please";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;

    private long showId;
    private String cacheKey;

    @BeforeEach
    void setUp() {
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 day'
                ORDER BY s.id LIMIT 1
                """, Long.class);
        cacheKey = SeatIntentParser.CACHE_PREFIX
                + AiGateway.sha256(SeatIntentParser.normalize(TEXT) + "|GOLD,PLATINUM,SILVER");
    }

    @AfterEach
    void clearCache() {
        redis.delete(cacheKey);
    }

    private String bearer(Role role) {
        User user = users.save(new User("ask-" + UUID.randomUUID() + "@example.com", "{bcrypt}unused", "Ask",
                role, AuthProvider.LOCAL));
        return "Bearer " + tokens.issueAccessToken(
                new AuthenticatedUser(user.getId(), user.getEmail(), role, "unused")).value();
    }

    private org.springframework.test.web.servlet.ResultActions ask(String auth, long show) throws Exception {
        var request = post("/api/v1/shows/{id}/seat-finder/ask", show)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + TEXT + "\"}");
        if (auth != null) {
            request.header(HttpHeaders.AUTHORIZATION, auth);
        }
        return mvc.perform(request);
    }

    @Test
    void withoutAModelTheUserIsSentToTheManualForm() throws Exception {
        ask(bearer(Role.USER), showId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.understood").value(false))
                .andExpect(jsonPath("$.fallbackReason").value("DISABLED"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void cachedIntentRunsTheFinderWithoutAModel() throws Exception {
        redis.opsForValue().set(cacheKey, """
                {"count":2,"budgetRupees":null,"budgetIsTotal":false,"sections":[],"aisle":true,"rows":"FRONT"}""",
                Duration.ofMinutes(5));

        ask(bearer(Role.USER), showId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.understood").value(true))
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.filters.count").value(2))
                .andExpect(jsonPath("$.filters.aisle").value(true))
                .andExpect(jsonPath("$.filters.rows").value("FRONT"))
                .andExpect(jsonPath("$.result.options[0].hasAisleSeat").value(true))
                .andExpect(jsonPath("$.result.options[0].seatIds.length()").value(2));
    }

    @Test
    void anonymousAndUnknownShow() throws Exception {
        ask(null, showId).andExpect(status().isUnauthorized());
        ask(bearer(Role.USER), 999_999).andExpect(status().isNotFound());
    }

    @Test
    void adminEvalEndpointIsAdminOnlyAndFallsBackWithoutAModel() throws Exception {
        mvc.perform(post("/api/v1/admin/ai/seat-intent").header(HttpHeaders.AUTHORIZATION, bearer(Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"2 seats\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/ai/seat-intent").header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"2 seats\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.understood").value(false))
                .andExpect(jsonPath("$.fallbackReason").value("DISABLED"));
    }
}
