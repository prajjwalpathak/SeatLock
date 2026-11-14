package com.seatlock.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/** The V5 demo logins work with the public password and get the right roles. */
@IntegrationTest
class DemoAccountsIntegrationTest {

    static final String DEMO_PASSWORD = "seatlock-demo";

    @Autowired MockMvc mvc;
    @Autowired JwtDecoder jwtDecoder;

    @Test
    void demoUserLogsInAsUser() throws Exception {
        assertThat(rolesOf(login("demo.user@seatlock.test"))).containsExactly("USER");
    }

    @Test
    void demoOrganizerLogsInAndSeesTheirEvents() throws Exception {
        String token = login("organizer.cinema@seatlock.test");
        assertThat(rolesOf(token)).containsExactly("ORGANIZER");

        String body = mvc.perform(get("/api/v1/organizer/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(body, "$")).isNotEmpty();
    }

    @Test
    void otherSeedOrganizersStayLockedAndNoAdminIsSeeded() throws Exception {
        mvc.perform(loginRequest("organizer.live@seatlock.test")).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest("admin@seatlock.test")).andExpect(status().isUnauthorized());
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(loginRequest(email)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static org.springframework.test.web.servlet.RequestBuilder loginRequest(String email) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, DEMO_PASSWORD));
    }

    private List<String> rolesOf(String token) {
        return jwtDecoder.decode(token).getClaimAsStringList("roles");
    }
}
