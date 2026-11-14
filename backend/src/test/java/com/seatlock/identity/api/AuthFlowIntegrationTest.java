package com.seatlock.identity.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/** The whole journey over HTTP only, the way the SPA will do it (incl. CORS). */
@IntegrationTest
class AuthFlowIntegrationTest {

    static final String FRONTEND = "http://localhost:5173";

    @Autowired MockMvc mvc;

    @Test
    void registerThenLoginThenCallProtectedEndpointFromTheFrontendOrigin() throws Exception {
        String email = "flow-" + UUID.randomUUID() + "@example.com";
        String credentials = """
                {"email":"%s","password":"correct-horse-battery"}""".formatted(email);

        // Browser preflight for a JSON POST to /auth/login
        mvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, FRONTEND)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));

        mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.ORIGIN, FRONTEND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials.replace("}", ",\"displayName\":\"Flow User\"}")))
                .andExpect(status().isCreated());

        String tokenJson = mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.ORIGIN, FRONTEND)
                        .contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)) // stateless: no session
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(tokenJson, "$.accessToken");

        mvc.perform(get("/api/v1/me").header(HttpHeaders.ORIGIN, FRONTEND)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("Flow User"));
    }
}
