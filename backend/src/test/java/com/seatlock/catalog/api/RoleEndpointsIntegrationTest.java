package com.seatlock.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import testsupport.IntegrationTest;

/**
 * What the admin and organizer endpoints return. jwt() skips signing; real-token role checks
 * are in the security matrix test.
 */
@IntegrationTest
class RoleEndpointsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void adminListsUsersPagedWithoutPasswordHashes() throws Exception {
        long total = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);

        mvc.perform(get("/api/v1/admin/users").param("size", "2").with(as(1, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value((int) Math.min(2, total)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(total))
                .andExpect(jsonPath("$.items[0].email").exists())
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
    }

    @Test
    void adminPageSizeIsCapped() throws Exception {
        mvc.perform(get("/api/v1/admin/users").param("size", "500").with(as(1, "ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void organizerSeesExactlyTheirOwnEventsIncludingDrafts() throws Exception {
        long organizerId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = 'organizer.cinema@seatlock.test'", Long.class);
        List<Long> expectedIds = jdbc.queryForList(
                "SELECT id FROM events WHERE organizer_id = ? ORDER BY id DESC", Long.class, organizerId);

        String body = mvc.perform(get("/api/v1/organizer/events").with(as(organizerId, "ORGANIZER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Number> ids = JsonPath.read(body, "$[*].id");
        assertThat(ids).extracting(Number::longValue).containsExactlyElementsOf(expectedIds);
        List<Integer> showCounts = JsonPath.read(body, "$[*].showCount");
        assertThat(showCounts).allSatisfy(c -> assertThat(c).isGreaterThanOrEqualTo(0));
    }

    @Test
    void organizerWithNoEventsGetsEmptyList() throws Exception {
        mvc.perform(get("/api/v1/organizer/events").with(as(999_999, "ORGANIZER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static RequestPostProcessor as(long userId, String role) {
        return jwt().jwt(j -> j.subject(Long.toString(userId)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
