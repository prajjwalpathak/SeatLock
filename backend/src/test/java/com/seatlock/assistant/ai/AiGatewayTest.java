package com.seatlock.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatlock.common.exception.InvalidRequestException;
import com.seatlock.common.ratelimit.RateLimitProperties.Limit;
import com.seatlock.common.ratelimit.RateLimitedException;
import com.seatlock.common.ratelimit.RateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Guardrails without a model: a stub LlmClient, real Resilience4j breaker/limiter, mocked quota buckets. */
class AiGatewayTest {

    private static final Limit ANY = new Limit(10, 10, Duration.ofMinutes(1));
    private static final long USER = 42L;

    private final RateLimiter limiter = mock(RateLimiter.class);
    private final AiAuditLog audit = mock(AiAuditLog.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<AiGateway> gateways = new ArrayList<>();
    private CircuitBreaker breaker;

    @BeforeEach
    void allowEverything() {
        when(limiter.tryConsume(anyString(), any())).thenReturn(new RateLimiter.Decision(true, 5, 0));
        breaker = CircuitBreaker.of("gemini", CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .ignoreExceptions(RejectedExecutionException.class)
                .build());
    }

    @AfterEach
    void closeGateways() {
        gateways.forEach(AiGateway::close);
    }

    private AiGateway gateway(LlmClient llm) {
        return gateway(llm, 2, 2);
    }

    private AiGateway gateway(LlmClient llm, int threads, int queue) {
        return gateway(llm, threads, queue, 300);
    }

    private AiGateway gateway(LlmClient llm, int threads, int queue, long timeoutMs) {
        AiProperties props = new AiProperties("test-model", 300, threads, queue, Duration.ofDays(30),
                new AiProperties.Quota(ANY, ANY, ANY));
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(timeoutMs)).cancelRunningFuture(true).build());
        AiGateway gateway = new AiGateway(llm, props, limiter, breaker, timeLimiter, audit, meters);
        gateways.add(gateway);
        return gateway;
    }

    /** Stub model: counts calls and runs {@code behaviour}. */
    private LlmClient llm(java.util.function.Supplier<LlmClient.LlmReply> behaviour) {
        return new LlmClient() {
            @Override
            public LlmReply complete(String systemPrompt, String userText) {
                calls.incrementAndGet();
                return behaviour.get();
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public String model() {
                return "test-model";
            }
        };
    }

    private double count(AiFeature feature, AiOutcome outcome) {
        return meters.get(AiGateway.METER_REQUESTS)
                .tag("feature", feature.name()).tag("outcome", outcome.name()).counter().count();
    }

    private AiAuditLog.Entry lastAudit() {
        ArgumentCaptor<AiAuditLog.Entry> captor = ArgumentCaptor.forClass(AiAuditLog.Entry.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void answerIsReturnedAndAuditedWithoutTheText() {
        AiGateway gateway = gateway(llm(() -> new LlmClient.LlmReply("  Hello there  ", 12, 3)));

        AiResult result = gateway.complete(AiFeature.PING, USER, "system", "hi");

        assertThat(result).isInstanceOfSatisfying(AiResult.Answer.class, a -> {
            assertThat(a.text()).isEqualTo("Hello there");
            assertThat(a.model()).isEqualTo("test-model");
        });
        AiAuditLog.Entry entry = lastAudit();
        assertThat(entry.outcome()).isEqualTo(AiOutcome.OK);
        assertThat(entry.inputChars()).isEqualTo(2);
        assertThat(entry.inputSha256()).isEqualTo(AiGateway.sha256("hi")).hasSize(64);
        assertThat(entry.promptTokens()).isEqualTo(12);
        assertThat(entry.completionTokens()).isEqualTo(3);
        assertThat(entry.latencyMs()).isNotNull();
        assertThat(count(AiFeature.PING, AiOutcome.OK)).isEqualTo(1);
        assertThat(count(AiFeature.SEAT_FINDER, AiOutcome.OK)).isZero();   // registered at 0 up front
    }

    @Test
    void rejectedAnswerIsInvalidOutputWithTokensAudited() {
        AiGateway gateway = gateway(llm(() -> new LlmClient.LlmReply("not json", 30, 4)));

        AiResult result = gateway.complete(AiFeature.SEAT_FINDER, USER, "s", "2 seats",
                AiGateway.Options.validatedBy(answer -> answer.startsWith("{")));

        assertThat(result).isEqualTo(new AiResult.Fallback(AiOutcome.INVALID_OUTPUT));
        AiAuditLog.Entry entry = lastAudit();
        assertThat(entry.outcome()).isEqualTo(AiOutcome.INVALID_OUTPUT);
        assertThat(entry.promptTokens()).isEqualTo(30);
        assertThat(count(AiFeature.SEAT_FINDER, AiOutcome.INVALID_OUTPUT)).isEqualTo(1);
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);   // the model did answer
    }

    @Test
    void evalRunsSkipTheUserQuotaButNotTheGlobalCaps() {
        AiGateway gateway = gateway(llm(() -> new LlmClient.LlmReply("{}", null, null)));

        assertThat(gateway.complete(AiFeature.SEAT_FINDER, USER, "s", "2 seats",
                AiGateway.Options.DEFAULT.withoutUserQuota())).isInstanceOf(AiResult.Answer.class);

        verify(limiter, never()).tryConsume(startsWith("ai:user-day:"), any());
        verify(limiter).tryConsume(eq("ai:global:day"), any());
    }

    @Test
    void featureWithItsOwnTimeLimitWaitsLonger() {
        AiProperties props = new AiProperties("test-model", 300, 2, 2, Duration.ofDays(30),
                new AiProperties.Quota(ANY, ANY, ANY));
        TimeLimiter fast = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(150)).build());
        TimeLimiter slow = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build());
        AiGateway gateway = new AiGateway(llm(() -> {
            sleep(500);
            return new LlmClient.LlmReply("ok", null, null);
        }), props, limiter, breaker, fast, java.util.Map.of(AiFeature.REFUND_ASSISTANT, slow), audit, meters);
        gateways.add(gateway);

        assertThat(gateway.complete(AiFeature.REFUND_ASSISTANT, USER, "s", "can I cancel")).isInstanceOf(AiResult.Answer.class);
        assertThat(gateway.complete(AiFeature.SEAT_FINDER, USER, "s", "2 seats")).isEqualTo(new AiResult.Fallback(AiOutcome.TIMEOUT));
    }

    @Test
    void sha256IsHexOfUtf8() {
        assertThat(AiGateway.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void disabledModelGivesFallbackWithoutSpendingQuota() {
        AiResult result = gateway(LlmClient.DISABLED).complete(AiFeature.PING, USER, "s", "hi");

        assertThat(result).isEqualTo(new AiResult.Fallback(AiOutcome.DISABLED));
        verify(limiter, never()).tryConsume(anyString(), any());
        assertThat(lastAudit().model()).isNull();
    }

    @Test
    void emptyOrTooLongInputIsRejectedBeforeAnything() {
        AiGateway gateway = gateway(llm(() -> new LlmClient.LlmReply("x", null, null)));

        assertThatThrownBy(() -> gateway.complete(AiFeature.PING, USER, "s", " "))
                .isInstanceOf(InvalidRequestException.class).hasFieldOrPropertyWithValue("code", "AI_INPUT_EMPTY");
        assertThatThrownBy(() -> gateway.complete(AiFeature.PING, USER, "s", "x".repeat(301)))
                .isInstanceOf(InvalidRequestException.class).hasFieldOrPropertyWithValue("code", "AI_INPUT_TOO_LONG");
        assertThat(gateway.complete(AiFeature.PING, USER, "s", "x".repeat(300))).isInstanceOf(AiResult.Answer.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void spentUserQuotaIs429AndAudited() {
        when(limiter.tryConsume(eq("ai:user-day:" + USER), any())).thenReturn(new RateLimiter.Decision(false, 0, 1800));
        AiGateway gateway = gateway(llm(() -> new LlmClient.LlmReply("x", null, null)));

        assertThatThrownBy(() -> gateway.complete(AiFeature.PING, USER, "s", "hi"))
                .isInstanceOf(RateLimitedException.class)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 1800L);
        assertThat(calls).hasValue(0);
        assertThat(lastAudit().outcome()).isEqualTo(AiOutcome.USER_QUOTA);
    }

    @Test
    void spentGlobalCapGivesQuotaFallback() {
        when(limiter.tryConsume(eq("ai:global:minute"), any())).thenReturn(new RateLimiter.Decision(false, 0, 30));
        AiResult result = gateway(llm(() -> new LlmClient.LlmReply("x", null, null)))
                .complete(AiFeature.SEAT_FINDER, USER, "s", "2 seats");

        assertThat(result).isEqualTo(new AiResult.Fallback(AiOutcome.QUOTA));
        assertThat(calls).hasValue(0);
        assertThat(count(AiFeature.SEAT_FINDER, AiOutcome.QUOTA)).isEqualTo(1);
    }

    @Test
    void slowModelTimesOut() {
        AiGateway gateway = gateway(llm(() -> {
            sleep(3_000);
            return new LlmClient.LlmReply("too late", null, null);
        }));

        long start = System.nanoTime();
        AiResult result = gateway.complete(AiFeature.PING, USER, "s", "hi");

        assertThat(result).isEqualTo(new AiResult.Fallback(AiOutcome.TIMEOUT));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000);
        assertThat(lastAudit().latencyMs()).isNotNull();
    }

    @Test
    void failuresOpenTheCircuitThenCallsAreSkippedWithoutSpendingGlobalQuota() {
        AiGateway gateway = gateway(llm(() -> {
            throw new IllegalStateException("429 RESOURCE_EXHAUSTED");
        }));

        for (int i = 0; i < 5; i++) {
            assertThat(gateway.complete(AiFeature.PING, USER, "s", "hi")).isEqualTo(new AiResult.Fallback(AiOutcome.ERROR));
        }
        assertThat(gateway.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        org.mockito.Mockito.clearInvocations(limiter);

        assertThat(gateway.complete(AiFeature.PING, USER, "s", "hi")).isEqualTo(new AiResult.Fallback(AiOutcome.CIRCUIT_OPEN));
        assertThat(calls).hasValue(5);
        verify(limiter, never()).tryConsume(startsWith("ai:global"), any());
    }

    @Test
    void circuitThatOpensJustBeforeTheCallIsCircuitOpen() {
        // State check passes (CLOSED); the breaker is forced open by the time it is asked for a permission
        AiGateway gateway = gateway(llm(() -> new LlmClient.LlmReply("x", null, null)));
        when(limiter.tryConsume(eq("ai:global:minute"), any())).thenAnswer(inv -> {
            breaker.transitionToForcedOpenState();
            return new RateLimiter.Decision(true, 1, 0);
        });

        assertThat(gateway.complete(AiFeature.PING, USER, "s", "hi")).isEqualTo(new AiResult.Fallback(AiOutcome.CIRCUIT_OPEN));
        assertThat(calls).hasValue(0);
        assertThat(gateway.complete(AiFeature.PING, USER, "s", "hi")).isEqualTo(new AiResult.Fallback(AiOutcome.CIRCUIT_OPEN));
    }

    @Test
    void blankAnswerIsAnError() {
        AiResult result = gateway(llm(() -> new LlmClient.LlmReply("   ", 5, 0))).complete(AiFeature.PING, USER, "s", "hi");

        assertThat(result).isEqualTo(new AiResult.Fallback(AiOutcome.ERROR));
        assertThat(lastAudit().promptTokens()).isEqualTo(5);
    }

    @Test
    void fullPoolIsBusyAndDoesNotCountAgainstTheModel() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AiGateway gateway = gateway(llm(() -> {
            entered.countDown();
            await(release);
            return new LlmClient.LlmReply("first", null, null);
        }), 1, 0, 5_000);   // long limit: the first call must still hold the only thread when the second arrives

        CompletableFuture<AiResult> first = CompletableFuture.supplyAsync(
                () -> gateway.complete(AiFeature.PING, USER, "s", "one"));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(gateway.complete(AiFeature.PING, USER, "s", "two")).isEqualTo(new AiResult.Fallback(AiOutcome.BUSY));
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();   // ignored exception
        release.countDown();
        first.get(2, TimeUnit.SECONDS);
    }

    @Test
    void fallbackCannotCarryOk() {
        assertThatThrownBy(() -> new AiResult.Fallback(AiOutcome.OK)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AiOutcome.OK.isFallback()).isFalse();
        assertThat(AiOutcome.TIMEOUT.isFallback()).isTrue();
    }

    @Test
    void disabledClientRefusesCalls() {
        assertThat(LlmClient.DISABLED.enabled()).isFalse();
        assertThat(LlmClient.DISABLED.model()).isEqualTo("none");
        assertThatThrownBy(() -> LlmClient.DISABLED.complete("s", "u")).isInstanceOf(IllegalStateException.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
