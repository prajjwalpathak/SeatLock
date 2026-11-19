package com.seatlock.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;

/** The interceptor's decisions, without Redis: which buckets are asked, when 429 is thrown, which header is set. */
class RateLimitInterceptorTest {

    static class Handlers {
        @RateLimited("holds")
        public void limited() {}

        @RateLimited("unknown")
        public void misconfigured() {}

        public void unlimited() {}
    }

    private static final RateLimitProperties.Limit IP = new RateLimitProperties.Limit(30, 30, Duration.ofMinutes(1));
    private static final RateLimitProperties.Limit USER = new RateLimitProperties.Limit(5, 10, Duration.ofMinutes(1));
    private static final RateLimitProperties PROPS =
            new RateLimitProperties(true, Map.of("holds", new RateLimitProperties.Policy(IP, USER)));

    private final RateLimiter limiter = mock(RateLimiter.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }

    private static void signedIn(long userId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(String.valueOf(userId))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void skipsWhenDisabledNotAControllerOrNotAnnotated() throws Exception {
        RateLimitInterceptor off = new RateLimitInterceptor(limiter, new RateLimitProperties(false, PROPS.policies()));
        RateLimitInterceptor on = new RateLimitInterceptor(limiter, PROPS);

        assertThat(off.preHandle(request, response, handler("limited"))).isTrue();
        assertThat(on.preHandle(request, response, new Object())).isTrue();          // e.g. a static resource
        assertThat(on.preHandle(request, response, handler("unlimited"))).isTrue();
        verifyNoInteractions(limiter);
    }

    @Test
    void anUnknownPolicyIsAConfigurationError() {
        RateLimitInterceptor on = new RateLimitInterceptor(limiter, PROPS);
        assertThatThrownBy(() -> on.preHandle(request, response, handler("misconfigured")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("policies.unknown");
    }

    @Test
    void anonymousCallersUseOnlyTheIpBucket() throws Exception {
        request.setRemoteAddr("203.0.113.7");
        when(limiter.tryConsume(eq("rl:holds:ip:203.0.113.7"), eq(IP))).thenReturn(new RateLimiter.Decision(true, 29, 0));

        assertThat(new RateLimitInterceptor(limiter, PROPS).preHandle(request, response, handler("limited"))).isTrue();
        assertThat(response.getHeader(RateLimitInterceptor.REMAINING_HEADER)).isEqualTo("29");
    }

    @Test
    void anEmptyIpBucketIs429WithRetryAfter() {
        when(limiter.tryConsume(any(), eq(IP))).thenReturn(new RateLimiter.Decision(false, 0, 12));

        assertThatThrownBy(() -> new RateLimitInterceptor(limiter, PROPS).preHandle(request, response, handler("limited")))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(12));
    }

    @Test
    void signedInUsersAlsoPayFromTheirOwnBucketAndSeeTheSmallerRemaining() throws Exception {
        signedIn(7);
        when(limiter.tryConsume(any(), eq(IP))).thenReturn(new RateLimiter.Decision(true, 29, 0));
        when(limiter.tryConsume(eq("rl:holds:user:7"), eq(USER))).thenReturn(new RateLimiter.Decision(true, 3, 0));

        new RateLimitInterceptor(limiter, PROPS).preHandle(request, response, handler("limited"));

        assertThat(response.getHeader(RateLimitInterceptor.REMAINING_HEADER)).isEqualTo("3");
    }

    @Test
    void anEmptyUserBucketIs429EvenWithIpTokensLeft() {
        signedIn(7);
        when(limiter.tryConsume(any(), eq(IP))).thenReturn(new RateLimiter.Decision(true, 29, 0));
        when(limiter.tryConsume(any(), eq(USER))).thenReturn(new RateLimiter.Decision(false, 0, 6));

        assertThatThrownBy(() -> new RateLimitInterceptor(limiter, PROPS).preHandle(request, response, handler("limited")))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(6));
    }

    @Test
    void unknownRemainingCountsSendNoHeader() throws Exception {
        // the limiter reports -1 when it failed open (Redis down): no header rather than a made-up number
        signedIn(7);
        when(limiter.tryConsume(any(), eq(IP))).thenReturn(new RateLimiter.Decision(true, -1, 0));
        when(limiter.tryConsume(any(), eq(USER))).thenReturn(new RateLimiter.Decision(true, -1, 0));

        new RateLimitInterceptor(limiter, PROPS).preHandle(request, response, handler("limited"));

        assertThat(response.getHeader(RateLimitInterceptor.REMAINING_HEADER)).isNull();
    }
}
