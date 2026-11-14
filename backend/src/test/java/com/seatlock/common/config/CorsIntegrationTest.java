package com.seatlock.common.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

@IntegrationTest
class CorsIntegrationTest {

    private static final String FRONTEND = "http://localhost:5173";

    @Autowired
    MockMvc mvc;

    @Test
    void preflightFromFrontendOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/v1/events")
                        .header(HttpHeaders.ORIGIN, FRONTEND)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
    }

    @Test
    void actualRequestExposesRequestIdToBrowserJs() throws Exception {
        mvc.perform(get("/api/v1/system/time").header(HttpHeaders.ORIGIN, FRONTEND))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Request-Id")));
    }

    @Test
    void unknownOriginIsRejected() throws Exception {
        mvc.perform(get("/api/v1/system/time").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
    }
}