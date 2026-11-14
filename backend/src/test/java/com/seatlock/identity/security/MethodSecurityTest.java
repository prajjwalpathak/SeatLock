package com.seatlock.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.RoleTestController;
import testsupport.SecurityRulesSlice;

@WebMvcTest(controllers = RoleTestController.class)
@Import(RoleTestController.class)
@SecurityRulesSlice
@ActiveProfiles("test")
class MethodSecurityTest {

    @Autowired
    MockMvc mvc;

    /** Role you have × endpoint you call → expected status. Higher roles inherit lower ones. */
    @ParameterizedTest(name = "{0} → /{1} = {2}")
    @CsvSource({
            "USER,      user,      200",
            "USER,      organizer, 403",
            "USER,      admin,     403",
            "ORGANIZER, user,      200",
            "ORGANIZER, organizer, 200",
            "ORGANIZER, admin,     403",
            "ADMIN,     user,      200",
            "ADMIN,     organizer, 200",
            "ADMIN,     admin,     200",
    })
    void roleMatrix(String role, String endpoint, int expectedStatus) throws Exception {
        mvc.perform(get("/test/roles/" + endpoint).with(user("u").roles(role)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void deniedResponseIsProblemDetailWithAccessDenied() throws Exception {
        mvc.perform(get("/test/roles/admin").with(user("u").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void anonymousCallerGets401NotForbidden() throws Exception {
        // /test/** is authenticated() in SecurityConfig, so this is the URL rule answering
        mvc.perform(get("/test/roles/user").with(anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void hierarchyGoesDownOnly() {
        var reachable = MethodSecurityConfig.roleHierarchy()
                .getReachableGrantedAuthorities(AuthorityUtils.createAuthorityList("ROLE_ORGANIZER"));

        assertThat(reachable).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ORGANIZER", "ROLE_USER");
    }
}
