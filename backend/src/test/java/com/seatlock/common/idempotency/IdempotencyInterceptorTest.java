package com.seatlock.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.seatlock.common.exception.ConflictException;
import com.seatlock.common.exception.InvalidRequestException;
import com.seatlock.common.exception.SeatLockException;
import com.seatlock.common.exception.UnprocessableRequestException;
import com.seatlock.common.idempotency.IdempotencyRecord.Status;
import com.seatlock.common.idempotency.IdempotencyStore.Claim;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/** The interceptor's decision table, with the store mocked. The real HTTP path is tested in HoldIdempotencyIntegrationTest. */
class IdempotencyInterceptorTest {

    private static final long USER = 7L;
    private static final String KEY = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b";
    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");
    private static final IdempotencyProperties PROPS = new IdempotencyProperties(Duration.ofHours(24), Duration.ofSeconds(30));

    record Body(List<Long> seatIds) {}

    /** Stand-in controller method: only its signature matters (where the @RequestBody is). */
    static class FakeController {
        @Idempotent
        public ResponseEntity<Object> create(long showId, @RequestBody Body body) {
            return null;
        }
    }

    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final IdempotencyInterceptor interceptor =
            new IdempotencyInterceptor(store, PROPS, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shows/42/holds");

    @BeforeEach
    void webRequestAndUser() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(String.valueOf(USER)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        request.addHeader(Idempotent.HEADER, KEY);
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void aMissingOrMalformedKeyIsRejectedBeforeAnythingRuns() throws Throwable {
        request.removeHeader(Idempotent.HEADER);
        assertCode(invocation(List.of(1L), ok()), InvalidRequestException.class, "IDEMPOTENCY_KEY_REQUIRED");

        request.addHeader(Idempotent.HEADER, "short");
        assertCode(invocation(List.of(1L), ok()), InvalidRequestException.class, "INVALID_IDEMPOTENCY_KEY");

        request.removeHeader(Idempotent.HEADER);
        request.addHeader(Idempotent.HEADER, "has spaces and\nnewlines!!");
        assertCode(invocation(List.of(1L), ok()), InvalidRequestException.class, "INVALID_IDEMPOTENCY_KEY");
        verifyNoInteractions(store);
    }

    @Test
    void firstRequestRunsAndItsResponseIsStored() throws Throwable {
        when(store.claim(eq(USER), eq(KEY), anyString(), anyString(), eq(NOW), eq(PROPS.ttl())))
                .thenReturn(new Claim.Claimed(5L));
        MethodInvocation call = invocation(List.of(1L, 2L), ResponseEntity.status(HttpStatus.CREATED).body(new Body(List.of(9L))));

        Object result = interceptor.invoke(call);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        verify(call).proceed();
        verify(store).complete(5L, 201, "{\"seatIds\":[9]}", NOW);
    }

    @Test
    void aCompletedKeyWithTheSameRequestIsReplayedWithoutRunningAgain() throws Throwable {
        AtomicReference<String> hash = new AtomicReference<>();
        when(store.claim(anyLong(), anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            hash.set(inv.getArgument(3));
            return new Claim.Claimed(5L);
        });
        interceptor.invoke(invocation(List.of(1L), ok()));                     // learn this request's hash
        // Copy it first: re-stubbing calls store.claim(...) once, which runs the answer above with "" args.
        String learned = hash.get();
        when(store.claim(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(existing(Status.COMPLETED, learned, 201, "{\"holdId\":\"h1\"}"));
        MethodInvocation retry = invocation(List.of(1L), ok());

        ResponseEntity<?> replayed = (ResponseEntity<?>) interceptor.invoke(retry);

        verify(retry, never()).proceed();
        assertThat(replayed.getStatusCode().value()).isEqualTo(201);
        assertThat(replayed.getBody()).isEqualTo("{\"holdId\":\"h1\"}");
        assertThat(replayed.getHeaders().getFirst(Idempotent.REPLAYED_HEADER)).isEqualTo("true");
    }

    @Test
    void theSameKeyForADifferentRequestIs422() throws Throwable {
        when(store.claim(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(existing(Status.COMPLETED, "0".repeat(64), 201, "{}"));

        assertCode(invocation(List.of(1L), ok()), UnprocessableRequestException.class, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void aKeyStillInProgressIs409UnlessTheLeaseHasExpired() throws Throwable {
        when(store.claim(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> existing(Status.IN_PROGRESS, inv.getArgument(3), null, null)); // same request
        when(store.takeOver(eq(5L), eq(NOW), eq(PROPS.lease()))).thenReturn(false);

        assertCode(invocation(List.of(1L), ok()), ConflictException.class, "IDEMPOTENCY_IN_PROGRESS");

        when(store.takeOver(eq(5L), eq(NOW), eq(PROPS.lease()))).thenReturn(true);  // owner presumed dead
        MethodInvocation retry = invocation(List.of(1L), ok());
        interceptor.invoke(retry);

        verify(retry).proceed();
        verify(store).complete(eq(5L), eq(200), anyString(), eq(NOW));
    }

    @Test
    void aFailedRequestReleasesTheKeySoARetryRunsAgain() throws Throwable {
        when(store.claim(anyLong(), anyString(), anyString(), anyString(), any(), any())).thenReturn(new Claim.Claimed(5L));
        MethodInvocation call = invocation(List.of(1L), ok());
        when(call.proceed()).thenThrow(new ConflictException("SEATS_UNAVAILABLE", "taken"));

        assertCode(call, ConflictException.class, "SEATS_UNAVAILABLE");

        verify(store).release(5L);
        verify(store, never()).complete(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void ifStoringTheResponseFailsTheClientStillGetsItsResult() throws Throwable {
        when(store.claim(anyLong(), anyString(), anyString(), anyString(), any(), any())).thenReturn(new Claim.Claimed(5L));
        doThrow(new RuntimeException("db down")).when(store).complete(anyLong(), anyInt(), anyString(), any());
        ResponseEntity<Body> response = ok();

        assertThat(interceptor.invoke(invocation(List.of(1L), response))).isSameAs(response);
        verify(store, never()).release(anyLong()); // the operation DID happen; never pretend it didn't
    }

    @Test
    void theFingerprintCoversPathAndBody() throws Throwable {
        AtomicReference<String> first = new AtomicReference<>();
        when(store.claim(anyLong(), anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            first.compareAndSet(null, inv.getArgument(3));
            return new Claim.Claimed(5L);
        });
        interceptor.invoke(invocation(List.of(1L), ok()));
        String hashA = first.get();

        AtomicReference<String> other = new AtomicReference<>();
        when(store.claim(anyLong(), anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            other.set(inv.getArgument(3));
            return new Claim.Claimed(6L);
        });
        interceptor.invoke(invocation(List.of(2L), ok()));                 // other seats
        assertThat(other.get()).isNotEqualTo(hashA).hasSize(64);

        request.setRequestURI("/api/v1/shows/43/holds");                    // other show, same body
        interceptor.invoke(invocation(List.of(1L), ok()));
        assertThat(other.get()).isNotEqualTo(hashA);
    }

    // ------------------------------------------------------------------ helpers

    private static ResponseEntity<Body> ok() {
        return ResponseEntity.ok(new Body(List.of(1L)));
    }

    private static MethodInvocation invocation(List<Long> seatIds, Object result) throws Throwable {
        Method method = FakeController.class.getMethod("create", long.class, Body.class);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(method);
        when(invocation.getArguments()).thenReturn(new Object[] {42L, new Body(seatIds)});
        when(invocation.proceed()).thenReturn(result);
        return invocation;
    }

    private static Claim existing(Status status, String hash, Integer responseStatus, String body) {
        return new Claim.Existing(new IdempotencyRecord(5L, USER, KEY, "POST /api/v1/shows/{showId}/holds", hash,
                status, responseStatus, body, NOW.minusSeconds(1), NOW.plus(Duration.ofHours(24))));
    }

    private void assertCode(MethodInvocation call, Class<? extends SeatLockException> type, String code) {
        assertThatThrownBy(() -> interceptor.invoke(call)).isInstanceOf(type).extracting("code").isEqualTo(code);
    }
}
