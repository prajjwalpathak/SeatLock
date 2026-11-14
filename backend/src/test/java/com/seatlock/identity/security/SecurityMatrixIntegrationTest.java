package com.seatlock.identity.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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
}
