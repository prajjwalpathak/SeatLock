package com.seatlock.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.identity.application.GoogleAccountService;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.oauth.OneTimeCodeService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import testsupport.IntegrationTest;

/** Second half of Google sign-in: one-time code → our JWT, exactly once. */
@IntegrationTest
class OAuthCodeExchangeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired GoogleAccountService googleAccounts;
    @Autowired OneTimeCodeService codes;
    @Autowired JwtDecoder jwtDecoder;

    @Test
    void validCodeBecomesOurJwtForThatUserExactlyOnce() throws Exception {
        User user = googleAccounts.signIn("ex-" + UUID.randomUUID() + "@gmail.com", true, "Exchange Test");
        String code = codes.issue(user.getId()); // what the success handler put in the redirect

        String body = exchange(code)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(8 * 60 * 60))
                .andReturn().getResponse().getContentAsString();
        Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");

        exchange(code) // replay (e.g. the callback URL from browser history)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_OAUTH_CODE"));
    }

    @Test
    void unknownCodeIs401AndBlankIs400() throws Exception {
        exchange("guessed-code").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_OAUTH_CODE"))
                .andExpect(jsonPath("$.requestId").exists());
        exchange("").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void codeForADeletedUserIs401Not500() throws Exception {
        exchange(codes.issue(987_654_321L)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_OAUTH_CODE"));
    }

    @Test
    void spaCanCallItCrossOrigin() throws Exception {
        mvc.perform(options("/api/v1/auth/oauth/exchange")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    private ResultActions exchange(String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }
}
