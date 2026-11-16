package com.seatlock.common.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link RateLimitInterceptor} for the API; it only acts on {@link RateLimited} methods.
 * Dependencies are optional: {@code @WebMvcTest} slices load every WebMvcConfigurer but not the limiter
 * (it needs Redis), so there the interceptor is simply not registered.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<RateLimiter> limiter;
    private final ObjectProvider<RateLimitProperties> props;

    public RateLimitWebConfig(ObjectProvider<RateLimiter> limiter, ObjectProvider<RateLimitProperties> props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimiter l = limiter.getIfAvailable();
        RateLimitProperties p = props.getIfAvailable();
        if (l != null && p != null) {
            registry.addInterceptor(new RateLimitInterceptor(l, p)).addPathPatterns("/api/**");
        }
    }
}
