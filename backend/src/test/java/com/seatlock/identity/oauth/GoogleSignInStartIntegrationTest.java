package com.seatlock.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import testsupport.IntegrationTest;

/** Step 1 of Google sign-in: we redirect to Google with state, nonce and PKCE, and keep no session. */
@IntegrationTest
class GoogleSignInStartIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;

    @Test
    void redirectsToGoogleWithStateNonceAndPkceAndStoresTheRequestInRedis() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound())
                .andReturn().getResponse();

        UriComponents location = UriComponentsBuilder.fromUriString(response.getHeader(HttpHeaders.LOCATION)).build();
        MultiValueMap<String, String> query = location.getQueryParams();
        assertThat(location.getHost()).isEqualTo("accounts.google.com");
        assertThat(param(query, "response_type")).isEqualTo("code");
        assertThat(param(query, "client_id")).isEqualTo("test-client-id");
        assertThat(param(query, "scope")).contains("openid", "email", "profile");
        assertThat(param(query, "redirect_uri")).isEqualTo("http://localhost/login/oauth2/code/google");
        assertThat(param(query, "nonce")).isNotBlank();                       // OIDC replay protection
        assertThat(param(query, "code_challenge")).isNotBlank();              // PKCE
        assertThat(param(query, "code_challenge_method")).isEqualTo("S256");
        String state = param(query, "state");
        assertThat(state).isNotBlank();

        // Browser-binding cookie carries the same state; locked down
        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).singleElement().satisfies(c -> assertThat(c)
                .startsWith(RedisOAuth2AuthorizationRequestRepository.COOKIE + "=" + state)
                .contains("HttpOnly", "SameSite=Lax", "Path=/login/oauth2/", "Max-Age=300"));
        assertThat(cookies).noneMatch(c -> c.startsWith("JSESSIONID")); // stateless: no session

        // The request itself is in Redis for 5 minutes
        String key = RedisOAuth2AuthorizationRequestRepository.KEY_PREFIX + state;
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key)).isBetween(1L, 300L);
    }

    @Test
    void unknownProviderIsNotRedirectedAnywhere() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/oauth2/authorization/github")).andReturn().getResponse();
        assertThat(response.getStatus()).isGreaterThanOrEqualTo(400); // Spring's default for an unknown client id
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
    }

    private static String param(MultiValueMap<String, String> query, String name) {
        String raw = query.getFirst(name);
        return raw == null ? null : UriUtils.decode(raw, StandardCharsets.UTF_8);
    }
}
