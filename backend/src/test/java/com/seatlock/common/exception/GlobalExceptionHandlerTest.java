package com.seatlock.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.ErrorTestController;
import testsupport.SecurityRulesSlice;

@WebMvcTest(controllers = ErrorTestController.class)
@Import(ErrorTestController.class)
@SecurityRulesSlice
@ActiveProfiles("test")
@WithMockUser // the /test/** routes are protected; individual tests override this
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void notFoundExceptionBecomes404ProblemWithCodeAndRequestId() throws Exception {
        mvc.perform(get("/test/errors/not-found").header("X-Request-Id", "test-request-01"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Event 42 was not found"))
                .andExpect(jsonPath("$.requestId").value("test-request-01"));
    }

    @Test
    void conflictExceptionBecomes409WithDomainCode() throws Exception {
        mvc.perform(get("/test/errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_HELD"))
                .andExpect(jsonPath("$.detail").value("Seat A1 is already held"));
    }

    @Test
    void unexpectedExceptionBecomes500WithoutLeakingInternals() throws Exception {
        mvc.perform(get("/test/errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(not(containsString("hunter2"))))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void invalidBodyBecomes400WithFieldErrors() throws Exception {
        mvc.perform(post("/test/errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'quantity')]").exists());
    }

    @Test
    void malformedJsonBecomes400Problem() throws Exception {
        mvc.perform(post("/test/errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unknownRouteBecomes404ProblemFromBaseHandler() throws Exception {
        mvc.perform(get("/test/errors/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    @WithAnonymousUser
    void anonymousRequestToProtectedRouteBecomes401ProblemFromEntryPoint() throws Exception {
        mvc.perform(get("/test/errors/not-found").header("X-Request-Id", "test-request-02"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value("test-request-02"));
    }

    @Test
    void accessDeniedForSignedInUserBecomes403WithoutLeakingTheRule() throws Exception {
        mvc.perform(get("/test/errors/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail").value(not(containsString("ADMIN_ONLY"))))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void badCredentialsBecome401InvalidCredentialsWithoutLeakingTheReason() throws Exception {
        mvc.perform(get("/test/errors/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)) // not a token challenge
                .andExpect(jsonPath("$.detail").value(not(containsString("alice"))));
    }
}
