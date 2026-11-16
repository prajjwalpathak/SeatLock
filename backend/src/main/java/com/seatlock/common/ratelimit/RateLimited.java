package com.seatlock.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rate-limits a controller method with the named policy from {@code seatlock.rate-limit.policies}
 * (per client IP, and per signed-in user). Over the limit → 429 {@code RATE_LIMITED} + {@code Retry-After}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /** Policy name, e.g. "holds". */
    String value();
}
