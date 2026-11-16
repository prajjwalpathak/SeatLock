package com.seatlock.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link RateLimitInterceptor} for the API (it only acts on {@link RateLimited} methods).
 * <p>Declared as a {@code @Bean WebMvcConfigurer} inside a plain configuration (this class is NOT a
 * WebMvcConfigurer itself): {@code @WebMvcTest} slices only pick up WebMvcConfigurer <i>classes</i>, so they
 * don't get this (it needs Redis), while full contexts always do, with hard dependencies. An earlier version
 * looked the limiter up optionally and could silently skip registration in a full context (CI, W3·D5).</p>
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitWebConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebConfig.class);

    @Bean
    WebMvcConfigurer rateLimitInterceptorRegistration(RateLimiter limiter, RateLimitProperties props) {
        log.info("Rate limiting {} for policies {}", props.enabled() ? "enabled" : "DISABLED", props.policies().keySet());
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new RateLimitInterceptor(limiter, props)).addPathPatterns("/api/**");
            }
        };
    }
}
