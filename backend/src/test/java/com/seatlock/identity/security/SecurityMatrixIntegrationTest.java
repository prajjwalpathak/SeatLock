package com.seatlock.identity.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import testsupport.IntegrationTest;

/**
 * Who can call what, end to end: real users in the DB, real RS256 tokens from TokenService,
 * the real decoder, URL rules, @PreAuthorize and the role hierarchy all in play.
 * Add a row here for every new protected endpoint.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // set up users/tokens once for all rows
class SecurityMatrixIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;
    @Autowired JdbcTemplate jdbc;

    private final Map<Role, String> tokens = new EnumMap<>(Role.class);

    @BeforeAll
    void createOneUserPerRoleAndSignTokens() {
        for (Role role : Role.values()) {
            User user = users.save(new User(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                    "{bcrypt}unused", "Matrix " + role, role, AuthProvider.LOCAL));
            tokens.put(role, tokenService.issueAccessToken(
                    new AuthenticatedUser(user.getId(), user.getEmail(), role, "unused")).value());
        }
    }

    @ParameterizedTest(name = "{0} GET {1} → {2}")
    @CsvSource(nullValues = "-", value = {
            // caller,    endpoint,                  status, code
            "ANONYMOUS, /api/v1/events,              200, -",
            "USER,      /api/v1/events,              200, -",
            "ORGANIZER, /api/v1/events,              200, -",
            "ADMIN,     /api/v1/events,              200, -",

            "ANONYMOUS, /api/v1/me,                  401, AUTHENTICATION_REQUIRED",
            "USER,      /api/v1/me,                  200, -",
            "ORGANIZER, /api/v1/me,                  200, -",
            "ADMIN,     /api/v1/me,                  200, -",

            "ANONYMOUS, /api/v1/organizer/events,    401, AUTHENTICATION_REQUIRED",
            "USER,      /api/v1/organizer/events,    403, ACCESS_DENIED",
            "ORGANIZER, /api/v1/organizer/events,    200, -",
            "ADMIN,     /api/v1/organizer/events,    200, -",   // hierarchy: ADMIN ⊃ ORGANIZER

            "ANONYMOUS, /api/v1/admin/users,         401, AUTHENTICATION_REQUIRED",
            "USER,      /api/v1/admin/users,         403, ACCESS_DENIED",
            "ORGANIZER, /api/v1/admin/users,         403, ACCESS_DENIED",
            "ADMIN,     /api/v1/admin/users,         200, -",
    })
    void accessMatrix(String caller, String endpoint, int expectedStatus, String expectedCode) throws Exception {
        MockHttpServletRequestBuilder request = get(endpoint);
        if (!caller.equals("ANONYMOUS")) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get(Role.valueOf(caller)));
        }

        ResultActions result = mvc.perform(request).andExpect(status().is(expectedStatus));
        if (expectedCode != null) {
            result.andExpect(jsonPath("$.code").value(expectedCode))
                    .andExpect(jsonPath("$.requestId").exists());
        }
    }

    /**
     * Writes: create show. The matrix's ORGANIZER and ADMIN own no events, so they pass the role
     * gates but get 404 from the ownership check (someone else's event is "not found").
     */
    @ParameterizedTest(name = "{0} POST /organizer/events/:id/shows → {1}") // MessageFormat: no literal braces
    @CsvSource(nullValues = "-", value = {
            "ANONYMOUS, 401, AUTHENTICATION_REQUIRED",
            "USER,      403, ACCESS_DENIED",
            "ORGANIZER, 404, NOT_FOUND",
            "ADMIN,     404, NOT_FOUND",
    })
    void createShowMatrix(String caller, int expectedStatus, String expectedCode) throws Exception {
        long someoneElsesEvent = jdbc.queryForObject("SELECT min(id) FROM events", Long.class);
        String body = """
                {"venueId":1,"startsAt":"2099-01-01T10:00:00Z","sectionPricesPaise":{"GOLD":100000}}""";
        MockHttpServletRequestBuilder request = post("/api/v1/organizer/events/{id}/shows", someoneElsesEvent)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (!caller.equals("ANONYMOUS")) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get(Role.valueOf(caller)));
        }

        mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    /** Holds: any signed-in role may use them; an unknown hold is 404 for everyone signed in. */
    @ParameterizedTest(name = "{0} GET + DELETE /holds/:id → {1}")
    @CsvSource({
            "ANONYMOUS, 401, AUTHENTICATION_REQUIRED",
            "USER,      404, NOT_FOUND",
            "ORGANIZER, 404, NOT_FOUND",
            "ADMIN,     404, NOT_FOUND",
    })
    void holdsMatrix(String caller, int expectedStatus, String expectedCode) throws Exception {
        String unknownHold = UUID.randomUUID().toString();
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/v1/holds/{id}", unknownHold),
                delete("/api/v1/holds/{id}", unknownHold))) {
            if (!caller.equals("ANONYMOUS")) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get(Role.valueOf(caller)));
            }
            mvc.perform(request)
                    .andExpect(status().is(expectedStatus))
                    .andExpect(jsonPath("$.code").value(expectedCode));
        }
    }

    @org.junit.jupiter.api.Test
    void anonymousCannotCreateAHold() throws Exception {
        mvc.perform(post("/api/v1/shows/{id}/holds", 1).contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    /** Bookings: any signed-in role; unknown bookings / holds are 404 for everyone signed in. */
    @ParameterizedTest(name = "{0} GET + cancel /bookings/:id, POST /holds/:id/booking → {1}")
    @CsvSource({
            "ANONYMOUS, 401, AUTHENTICATION_REQUIRED",
            "USER,      404, NOT_FOUND",
            "ORGANIZER, 404, NOT_FOUND",
            "ADMIN,     404, NOT_FOUND",
    })
    void bookingsMatrix(String caller, int expectedStatus, String expectedCode) throws Exception {
        String unknown = UUID.randomUUID().toString();
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/v1/bookings/{id}", unknown),
                post("/api/v1/bookings/{id}/cancel", unknown),
                post("/api/v1/holds/{id}/booking", unknown))) {
            if (!caller.equals("ANONYMOUS")) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get(Role.valueOf(caller)));
            }
            mvc.perform(request)
                    .andExpect(status().is(expectedStatus))
                    .andExpect(jsonPath("$.code").value(expectedCode));
        }
    }
}
