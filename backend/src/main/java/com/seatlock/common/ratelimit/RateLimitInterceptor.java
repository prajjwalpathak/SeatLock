package com.seatlock.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link RateLimited} policies before the controller runs (after Spring Security, so the user is known).
 * Checks the IP bucket, then the user bucket; the first one that is empty → {@link RateLimitedException} (429).
 * Behind Nginx the client IP is correct because {@code server.forward-headers-strategy: native} is set in prod.
 * Note: it runs before {@code @Idempotent}, so an idempotent replay also costs a token (cheap, and bounded).
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String REMAINING_HEADER = "RateLimit-Remaining";

    private final RateLimiter limiter;
    private final RateLimitProperties props;

    public RateLimitInterceptor(RateLimiter limiter, RateLimitProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!props.enabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited annotation = method.getMethodAnnotation(RateLimited.class);
        if (annotation == null) {
            return true;
        }
        RateLimitProperties.Policy policy = props.policies().get(annotation.value());
        if (policy == null) {
            throw new IllegalStateException("No seatlock.rate-limit.policies." + annotation.value() + " configured");
        }

        RateLimiter.Decision ip = limiter.tryConsume(
                "rl:" + annotation.value() + ":ip:" + request.getRemoteAddr(), policy.perIp());
        if (!ip.allowed()) {
            throw new RateLimitedException(ip.retryAfterSeconds());
        }
        long remaining = ip.remaining();
        Long userId = currentUserId();
        if (userId != null) {
            RateLimiter.Decision user = limiter.tryConsume(
                    "rl:" + annotation.value() + ":user:" + userId, policy.perUser());
            if (!user.allowed()) {
                throw new RateLimitedException(user.retryAfterSeconds());
            }
            remaining = Math.min(remaining < 0 ? Long.MAX_VALUE : remaining,
                    user.remaining() < 0 ? Long.MAX_VALUE : user.remaining());
        }
        if (remaining >= 0 && remaining != Long.MAX_VALUE) {
            response.setHeader(REMAINING_HEADER, String.valueOf(remaining));
        }
        return true;
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt ? Long.valueOf(jwt.getSubject()) : null;
    }
}
