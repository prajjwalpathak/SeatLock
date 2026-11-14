package com.seatlock.identity.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import tools.jackson.databind.json.JsonMapper;

/**
 * Remembers an in-flight Google sign-in between "redirect to Google" and "Google calls us back"
 * WITHOUT an HTTP session, so the callback can land on any instance.
 * <ul>
 *   <li>The request (state, nonce, PKCE verifier...) is stored in Redis under its {@code state},
 *       for 5 minutes, and can be used once (GETDEL).</li>
 *   <li>A short-lived HttpOnly cookie holds the same {@code state}. The callback must carry
 *       both, which ties it to the browser that started the sign-in (blocks login CSRF).</li>
 *   <li>Stored as plain JSON fields, never Java-serialized (no deserialization of untrusted bytes).</li>
 * </ul>
 */
public class RedisOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String COOKIE = "SL_OAUTH2_STATE";
    static final String KEY_PREFIX = "seatlock:oauth2:authreq:";
    static final Duration TTL = Duration.ofMinutes(5);
    private static final String COOKIE_PATH = "/login/oauth2/"; // only sent on the callback

    /** The fields needed to rebuild the request. All values are strings in practice. */
    record StoredRequest(String authorizationUri, String clientId, String redirectUri, Set<String> scopes,
                         String state, Map<String, String> additionalParameters,
                         Map<String, String> attributes, String authorizationRequestUri) {}

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RedisOAuth2AuthorizationRequestRepository(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        String state = authorizationRequest.getState();
        redis.opsForValue().set(KEY_PREFIX + state, json.writeValueAsString(toStored(authorizationRequest)), TTL);
        writeCookie(request, response, state, TTL);
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = verifiedState(request);
        return state == null ? null : fromJson(redis.opsForValue().get(KEY_PREFIX + state));
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        String state = verifiedState(request);
        writeCookie(request, response, "", Duration.ZERO); // always clear the cookie
        return state == null ? null : fromJson(redis.opsForValue().getAndDelete(KEY_PREFIX + state)); // single use
    }

    /** The {@code state} query param, only if the browser's cookie carries the same value. */
    private static String verifiedState(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        String cookieState = readCookie(request);
        if (state == null || cookieState == null) {
            return null;
        }
        boolean same = MessageDigest.isEqual( // constant-time comparison
                state.getBytes(StandardCharsets.UTF_8), cookieState.getBytes(StandardCharsets.UTF_8));
        return same ? state : null;
    }

    private static String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static void writeCookie(HttpServletRequest request, HttpServletResponse response,
                                    String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value)
                .httpOnly(true)               // JavaScript can't read it
                .secure(request.isSecure())   // HTTPS in prod (needs forwarded headers behind Nginx, W2·D5)
                .sameSite("Lax")              // still sent on Google's top-level redirect back to us
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static StoredRequest toStored(OAuth2AuthorizationRequest r) {
        return new StoredRequest(r.getAuthorizationUri(), r.getClientId(), r.getRedirectUri(), r.getScopes(),
                r.getState(), asStrings(r.getAdditionalParameters()), asStrings(r.getAttributes()),
                r.getAuthorizationRequestUri());
    }

    private OAuth2AuthorizationRequest fromJson(String value) {
        if (value == null) {
            return null; // expired, already used, or never existed
        }
        StoredRequest s = json.readValue(value, StoredRequest.class);
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(s.authorizationUri())
                .clientId(s.clientId())
                .redirectUri(s.redirectUri())
                .scopes(s.scopes())
                .state(s.state())
                .additionalParameters(params -> params.putAll(s.additionalParameters()))
                .attributes(attrs -> attrs.putAll(s.attributes()))
                .authorizationRequestUri(s.authorizationRequestUri())
                .build();
    }

    private static Map<String, String> asStrings(Map<String, Object> map) {
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, String.valueOf(v)));
        return out;
    }
}
