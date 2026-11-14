package com.seatlock.identity.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * Injects the signed-in user's id (the JWT "sub") into a controller parameter:
 * {@code public List<X> mine(@CurrentUserId long userId)}. Other modules use this instead of
 * touching JWT classes themselves.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal(expression = "T(java.lang.Long).parseLong(subject)")
public @interface CurrentUserId {
}
