package com.seatlock.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a controller method idempotent via the {@code Idempotency-Key} request header (required).
 * <ul>
 *   <li>First request with a key: runs, and a 2xx response is stored for 24 h.</li>
 *   <li>Same key, same request: the stored status + body are replayed ({@code Idempotent-Replayed: true}).</li>
 *   <li>Same key, different request: 422 {@code IDEMPOTENCY_KEY_REUSED}.</li>
 *   <li>Same key while the first is still running: 409 {@code IDEMPOTENCY_IN_PROGRESS}.</li>
 *   <li>Failed request (any exception): the key is released, so a retry runs again. Only use this on
 *       operations whose failures have no side effects (e.g. all-or-nothing holds).</li>
 * </ul>
 * The caller must be authenticated: keys are scoped per user.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    String HEADER = "Idempotency-Key";
    String REPLAYED_HEADER = "Idempotent-Replayed";
}
