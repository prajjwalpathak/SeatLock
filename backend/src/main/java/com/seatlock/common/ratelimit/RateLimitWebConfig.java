package com.seatlock.common.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link RateLimitInterceptor} for the API; it only acts on {@link RateLimited} methods. */
@Configuration(proxyBeanMethods = false)
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final RateLimiter limiter;
    private final RateLimitProperties props;

    public RateLimitWebConfig(RateLimiter limiter, RateLimitProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(limiter, props)).addPathPatterns("/api/**");
    }
}
