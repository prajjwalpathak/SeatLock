package com.seatlock.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.identity.application.UserService;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import testsupport.IntegrationTest;

@IntegrationTest
class LoginIntegrationTest {

    static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired JwtDecoder jwtDecoder;

    @Test
    void validLoginReturnsBearerTokenTheDecoderAccepts() throws Exception {
        User user = newUser();

        String body = login(user.getEmail().toUpperCase(), PASSWORD) // e-mail is case-insensitive
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(8 * 60 * 60)) // 8 h
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveIdenticalAnswers() throws Exception {
        User user = newUser();

        String wrongPassword = login(user.getEmail(), "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutRequestId(unknownEmail)).isEqualTo(withoutRequestId(wrongPassword));
    }

    @Test
    void lockedSeedOrganizerCannotLogInAndDoesNotCause500() throws Exception {
        login("organizer.live@seatlock.test", "!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void googleOnlyAccountCannotUsePasswordLogin() throws Exception {
        String email = "google-" + UUID.randomUUID() + "@example.com";
        users.save(new User(email, null, "Google User", Role.USER, AuthProvider.GOOGLE));

        login(email, "!").andExpect(status().isUnauthorized());
        login(email, "").andExpect(status().isBadRequest()); // blank → validation, before any lookup
    }

    @Test
    void passwordOver72BytesIsJustInvalidCredentials() throws Exception {
        User user = newUser();
        login(user.getEmail(), "x".repeat(100))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private User newUser() {
        return userService.register("login-" + UUID.randomUUID() + "@example.com", PASSWORD, "Login Test");
    }

    private ResultActions login(String email, String password) throws Exception {
        String json = """
                {"email":"%s","password":"%s"}""".formatted(email, password);
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String withoutRequestId(String problemJson) {
        return problemJson.replaceAll("\"requestId\":\"[^\"]*\"", "");
    }
}
