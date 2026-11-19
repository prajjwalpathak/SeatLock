package com.seatlock.common.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.common.web.InstanceHeaderFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.SecurityRulesSlice;

@WebMvcTest(controllers = SystemController.class,
        properties = {"seatlock.build.revision=0123456789abcdef", "seatlock.instance-id=app-2"})
@SecurityRulesSlice // real URL rules: proves the endpoint is public
@ActiveProfiles("test")
class SystemControllerTest {

    static final Instant FIXED = Instant.parse("2026-11-11T10:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary // wins even if the app's ClockConfig were ever loaded in this slice
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void returnsServerTimeInUtcAndIsNeverCached() throws Exception {
        mvc.perform(get("/api/v1/system/time"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.serverTime").value("2026-11-11T10:00:00Z"))
                .andExpect(jsonPath("$.epochMillis").value(FIXED.toEpochMilli()));
    }

    @Test
    void reportsTheBuildRevisionPubliclyAndUncached() throws Exception {
        mvc.perform(get("/api/v1/system/version"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.revision").value("0123456789abcdef"))
                .andExpect(jsonPath("$.instance").value("app-2"))
                .andExpect(header().string(InstanceHeaderFilter.HEADER, "app-2"));
    }
}