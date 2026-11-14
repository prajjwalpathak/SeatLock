package com.seatlock.identity.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/** The URL rules of SecurityConfig, exercised through the full filter chain. */
@IntegrationTest
class SecurityRulesIntegrationTest {

    private static final String FRONTEND = "http://localhost:5173";

    @Autowired
    MockMvc mvc;

    @Test
    void catalogReadsArePublicAndCreateNoSession() throws Exception {
        mvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void anythingNotListedRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void publicReadPathIsNotPublicForWrites() throws Exception {
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preflightToProtectedRouteSucceedsWithoutToken() throws Exception {
        mvc.perform(options("/api/v1/bookings")
                        .header(HttpHeaders.ORIGIN, FRONTEND)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
    }
}
