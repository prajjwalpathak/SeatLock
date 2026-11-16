package com.seatlock.common.idempotency;

import com.seatlock.common.exception.ConflictException;
import com.seatlock.common.exception.InvalidRequestException;
import com.seatlock.common.exception.UnprocessableRequestException;
import com.seatlock.common.idempotency.IdempotencyRecord.Status;
import com.seatlock.common.idempotency.IdempotencyStore.Claim;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs around {@link Idempotent} controller methods (registered by {@link IdempotencyConfig}, innermost,
 * so authentication and {@code @PreAuthorize} have already passed).
 * <p>The fingerprint is SHA-256 of {@code METHOD path} + the request body re-serialized as JSON, so the same
 * request with different whitespace or field order still matches, while another show or other seats don't.</p>
 */
public class IdempotencyInterceptor implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);
    /** UUIDs and similar opaque tokens; nothing that could smuggle odd characters into logs. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public IdempotencyInterceptor(IdempotencyStore store, IdempotencyProperties props, JsonMapper json, Clock clock) {
        this.store = store;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        HttpServletRequest request = currentRequest();
        String key = request.getHeader(Idempotent.HEADER);
        if (key == null || key.isBlank()) {
            throw new InvalidRequestException("IDEMPOTENCY_KEY_REQUIRED",
                    "Send an " + Idempotent.HEADER + " header (e.g. a new UUID per attempt)");
        }
        if (!KEY.matcher(key).matches()) {
            throw new InvalidRequestException("INVALID_IDEMPOTENCY_KEY",
                    Idempotent.HEADER + " must be 8–64 characters: letters, digits, '-' or '_'");
        }
        long userId = currentUserId();
        String endpoint = request.getMethod() + " " + pattern(request);
        String hash = fingerprint(request, invocation);

        long claimId;
        switch (store.claim(userId, key, endpoint, hash, clock.instant(), props.ttl())) {
            case Claim.Claimed claimed -> claimId = claimed.id();
            case Claim.Existing existing -> {
                IdempotencyRecord record = existing.record();
                if (!record.requestHash().equals(hash)) {
                    throw new UnprocessableRequestException("IDEMPOTENCY_KEY_REUSED",
                            "This " + Idempotent.HEADER + " was already used for a different request");
                }
                if (record.status() == Status.COMPLETED) {
                    return replay(record);
                }
                if (!store.takeOver(record.id(), clock.instant(), props.lease())) {
                    throw new ConflictException("IDEMPOTENCY_IN_PROGRESS",
                            "A request with this " + Idempotent.HEADER + " is still being processed; retry shortly");
                }
                log.warn("Took over stale idempotency key {} (claim {}) after the lease", key, record.id());
                claimId = record.id();
            }
        }

        Object result;
        try {
            result = invocation.proceed();
        } catch (Throwable e) {
            store.release(claimId); // failures have no side effects for @Idempotent operations: let a retry run again
            throw e;
        }
        try {
            store.complete(claimId, statusOf(result), json.writeValueAsString(bodyOf(result)), clock.instant());
        } catch (RuntimeException e) {
            // The operation succeeded; don't fail the client because we couldn't record it. A retry after the
            // lease re-runs it and gets the operation's own answer (e.g. HOLD_EXISTS with the holdId).
            log.error("Could not store idempotent response for key {} (claim {})", key, claimId, e);
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private static ResponseEntity<String> replay(IdempotencyRecord record) {
        return ResponseEntity.status(record.responseStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .header(Idempotent.REPLAYED_HEADER, "true")
                .body(record.responseBody());
    }

    private static int statusOf(Object result) {
        return result instanceof ResponseEntity<?> entity ? entity.getStatusCode().value() : HttpStatus.OK.value();
    }

    private static Object bodyOf(Object result) {
        return result instanceof ResponseEntity<?> entity ? entity.getBody() : result;
    }

    private String fingerprint(HttpServletRequest request, MethodInvocation invocation) {
        Object body = null;
        Parameter[] params = invocation.getMethod().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(RequestBody.class)) {
                body = invocation.getArguments()[i];
            }
        }
        String canonical = request.getMethod() + " " + request.getRequestURI() + "\n" + json.writeValueAsString(body);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static String pattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        throw new IllegalStateException("@Idempotent only works on web requests");
    }

    /** Same identity as {@code @CurrentUserId}: the JWT subject. */
    private static long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return Long.parseLong(jwt.getSubject());
        }
        throw new IllegalStateException("@Idempotent requires an authenticated (JWT) caller");
    }
}
