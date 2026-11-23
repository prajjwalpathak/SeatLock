package com.seatlock.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/** The test profile has no model (spring.ai.model.chat=none): the gateway must answer DISABLED, never fail. */
@IntegrationTest
class AdminAiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private String admin;
    private long adminId;

    @BeforeEach
    void signInAdmin() {
        User user = users.save(new User("ai-admin-" + UUID.randomUUID() + "@example.com", "{bcrypt}unused",
                "AI Admin", Role.ADMIN, AuthProvider.LOCAL));
        adminId = user.getId();
        admin = "Bearer " + tokens.issueAccessToken(
                new AuthenticatedUser(user.getId(), user.getEmail(), Role.ADMIN, "unused")).value();
    }

    @Test
    void statusShowsDisabledAndClosedCircuit() throws Exception {
        mvc.perform(get("/api/v1/admin/ai/status").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.model").value("none"))
                .andExpect(jsonPath("$.circuit").value("CLOSED"));
    }

    @Test
    void pingWithoutAModelIsADisabledFallbackAndAudited() throws Exception {
        mvc.perform(post("/api/v1/admin/ai/ping").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DISABLED"))
                .andExpect(jsonPath("$.answer").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT outcome FROM ai_requests WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, adminId)).isEqualTo("DISABLED");
    }

    @Test
    void overlongMessageIs400() throws Exception {
        mvc.perform(post("/api/v1/admin/ai/ping").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());
    }
}
