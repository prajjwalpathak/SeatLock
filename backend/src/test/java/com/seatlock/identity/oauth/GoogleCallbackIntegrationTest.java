package com.seatlock.identity.oauth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/** A callback we didn't start (forged, replayed or expired) goes back to the SPA's login page, never 500. */
@IntegrationTest
class GoogleCallbackIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void callbackWithUnknownStateRedirectsToLoginWithError() throws Exception {
        mvc.perform(get("/login/oauth2/code/google").param("code", "stolen-code").param("state", "never-issued"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:5173/login?error=GOOGLE_SIGN_IN_FAILED"));
    }

    @Test
    void userCancellingAtGoogleRedirectsToLoginWithError() throws Exception {
        mvc.perform(get("/login/oauth2/code/google").param("error", "access_denied").param("state", "whatever"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:5173/login?error=GOOGLE_SIGN_IN_FAILED"));
    }
}
