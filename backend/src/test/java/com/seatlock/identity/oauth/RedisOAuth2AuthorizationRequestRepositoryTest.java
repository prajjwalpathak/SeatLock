package com.seatlock.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import testsupport.IntegrationTest;
import tools.jackson.databind.json.JsonMapper;

/** Against real Redis (Testcontainers): round trip, single use, and the browser-binding cookie. */
@IntegrationTest
class RedisOAuth2AuthorizationRequestRepositoryTest {

    @Autowired StringRedisTemplate redis;
    @Autowired JsonMapper json;

    @Test
    void savedRequestComesBackIntactExactlyOnce() {
        var repository = new RedisOAuth2AuthorizationRequestRepository(redis, json);
        OAuth2AuthorizationRequest original = sampleRequest();
        repository.saveAuthorizationRequest(original, new MockHttpServletRequest(), new MockHttpServletResponse());

        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(callback(original.getState(), original.getState()));
        MockHttpServletResponse removeResponse = new MockHttpServletResponse();
        OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(
                callback(original.getState(), original.getState()), removeResponse);

        for (OAuth2AuthorizationRequest r : new OAuth2AuthorizationRequest[] {loaded, removed}) {
            assertThat(r).isNotNull();
            assertThat(r.getState()).isEqualTo(original.getState());
            assertThat(r.getClientId()).isEqualTo(original.getClientId());
            assertThat(r.getRedirectUri()).isEqualTo(original.getRedirectUri());
            assertThat(r.getScopes()).isEqualTo(original.getScopes());
            assertThat(r.getAuthorizationRequestUri()).isEqualTo(original.getAuthorizationRequestUri());
            assertThat(r.getAttributes()).isEqualTo(original.getAttributes());              // incl. PKCE verifier
            assertThat(r.getAdditionalParameters()).isEqualTo(original.getAdditionalParameters());
        }
        assertThat(removeResponse.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0"); // cookie cleared
        assertThat(repository.removeAuthorizationRequest(callback(original.getState(), original.getState()),
                new MockHttpServletResponse())).isNull(); // replay → nothing
    }

    @Test
    void callbackWithoutTheMatchingCookieFindsNothing() {
        var repository = new RedisOAuth2AuthorizationRequestRepository(redis, json);
        OAuth2AuthorizationRequest original = sampleRequest();
        repository.saveAuthorizationRequest(original, new MockHttpServletRequest(), new MockHttpServletResponse());

        // login-CSRF attempt: the attacker's state in the URL, but the victim's browser has no/other cookie
        assertThat(repository.removeAuthorizationRequest(callback(original.getState(), null),
                new MockHttpServletResponse())).isNull();
        assertThat(repository.removeAuthorizationRequest(callback(original.getState(), "other-state"),
                new MockHttpServletResponse())).isNull();
        // ...and the real owner can still finish
        assertThat(repository.removeAuthorizationRequest(callback(original.getState(), original.getState()),
                new MockHttpServletResponse())).isNotNull();
    }

    private static MockHttpServletRequest callback(String stateParam, String cookieState) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setParameter("state", stateParam);
        request.setParameter("code", "auth-code");
        if (cookieState != null) {
            request.setCookies(new Cookie(RedisOAuth2AuthorizationRequestRepository.COOKIE, cookieState));
        }
        return request;
    }

    private static OAuth2AuthorizationRequest sampleRequest() {
        String state = UUID.randomUUID().toString();
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("http://localhost/login/oauth2/code/google")
                .scopes(Set.of("openid", "email", "profile"))
                .state(state)
                .additionalParameters(Map.<String, Object>of("nonce", "n-123", "code_challenge", "cc", "code_challenge_method", "S256"))
                .attributes(Map.<String, Object>of("registration_id", "google", "nonce", "raw-nonce", "code_verifier", "verifier-xyz"))
                .authorizationRequestUri("https://accounts.google.com/o/oauth2/v2/auth?state=" + state)
                .build();
    }
}
