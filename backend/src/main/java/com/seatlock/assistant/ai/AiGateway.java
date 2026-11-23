package com.seatlock.assistant.ai;

import com.seatlock.common.exception.InvalidRequestException;
import com.seatlock.common.ratelimit.RateLimitedException;
import com.seatlock.common.ratelimit.RateLimiter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only way the app talks to a language model. Order of checks, cheapest first:
 * <ol>
 *   <li>input length (400 {@code AI_INPUT_TOO_LONG}); AI disabled → DISABLED</li>
 *   <li>per-user daily quota → 429 {@code RATE_LIMITED} (the per-minute limit is {@code @RateLimited("ai")} on the
 *       endpoint)</li>
 *   <li>circuit open → CIRCUIT_OPEN, without spending global quota</li>
 *   <li>global daily + per-minute caps (under Google's free tier) → QUOTA</li>
 *   <li>the call: on a bounded pool (BUSY when full), under a time limit (TIMEOUT), inside the circuit breaker
 *       (errors and timeouts count as failures)</li>
 * </ol>
 * Every request ends in an {@code ai_requests} row and a {@code seatlock.ai.requests{feature,outcome}} count.
 * Rule: <b>AI interprets, code decides</b>. Callers validate any answer and never let it write data or compute money.
 */
public class AiGateway implements AutoCloseable {

    static final String METER_REQUESTS = "seatlock.ai.requests";
    static final String METER_LATENCY = "seatlock.ai.latency";

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    private final LlmClient llm;
    private final AiProperties props;
    private final RateLimiter limiter;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final Map<AiFeature, TimeLimiter> timeLimiterOverrides;
    private final AiAuditLog audit;
    private final ExecutorService executor;
    private final Map<AiFeature, Map<AiOutcome, Counter>> counters = new EnumMap<>(AiFeature.class);
    private final Map<AiFeature, Timer> latency = new EnumMap<>(AiFeature.class);

    public AiGateway(LlmClient llm, AiProperties props, RateLimiter limiter, CircuitBreaker circuitBreaker,
                     TimeLimiter timeLimiter, AiAuditLog audit, MeterRegistry meters) {
        this(llm, props, limiter, circuitBreaker, timeLimiter, Map.of(), audit, meters);
    }

    /**
     * {@code timeLimiterOverrides}: features with their own limit (W7·D5: the Refund Assistant writes a few sentences
     * for someone reading their booking and gets 8 s; the Seat Finder keeps 4 s, someone is racing for seats).
     */
    public AiGateway(LlmClient llm, AiProperties props, RateLimiter limiter, CircuitBreaker circuitBreaker,
                     TimeLimiter timeLimiter, Map<AiFeature, TimeLimiter> timeLimiterOverrides, AiAuditLog audit,
                     MeterRegistry meters) {
        this.timeLimiterOverrides = Map.copyOf(timeLimiterOverrides);
        this.llm = llm;
        this.props = props;
        this.limiter = limiter;
        this.circuitBreaker = circuitBreaker;
        this.timeLimiter = timeLimiter;
        this.audit = audit;
        this.executor = newExecutor(props.executorThreads(), props.executorQueue());
        // Registered at 0 up front: lazily created counters are invisible to increase() until their 2nd sample (F12)
        for (AiFeature feature : AiFeature.values()) {
            Map<AiOutcome, Counter> byOutcome = new EnumMap<>(AiOutcome.class);
            for (AiOutcome outcome : AiOutcome.values()) {
                byOutcome.put(outcome, Counter.builder(METER_REQUESTS)
                        .description("AI requests by feature and outcome (everything but OK is a fallback)")
                        .tag("feature", feature.name()).tag("outcome", outcome.name())
                        .register(meters));
            }
            counters.put(feature, byOutcome);
            latency.put(feature, Timer.builder(METER_LATENCY)
                    .description("Time of answered LLM calls")
                    .tag("feature", feature.name())
                    .publishPercentileHistogram()
                    .register(meters));
        }
    }

    public boolean enabled() {
        return llm.enabled();
    }

    public String model() {
        return llm.model();
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    /**
     * Per-call options. {@code accept}: the caller's validation of the raw answer; a rejected answer ends as
     * {@code INVALID_OUTPUT} (audited, counted, fallback). {@code chargeUserQuota = false} skips the per-user daily
     * quota (admin eval runs only); the global caps always apply.
     */
    public record Options(Predicate<String> accept, boolean chargeUserQuota) {
        public static final Options DEFAULT = new Options(answer -> true, true);

        public static Options validatedBy(Predicate<String> accept) {
            return new Options(accept, true);
        }

        public Options withoutUserQuota() {
            return new Options(accept, false);
        }
    }

    /**
     * Ask the model. Never throws for "AI unavailable" (that's a {@link AiResult.Fallback}); throws only for caller
     * errors: input too long (400) or the user's daily AI quota spent (429).
     */
    public AiResult complete(AiFeature feature, long userId, String systemPrompt, String userText) {
        return complete(feature, userId, systemPrompt, userText, Options.DEFAULT);
    }

    public AiResult complete(AiFeature feature, long userId, String systemPrompt, String userText, Options options) {
        if (userText == null || userText.isBlank()) {
            throw new InvalidRequestException("AI_INPUT_EMPTY", "Please type a request");
        }
        if (userText.length() > props.maxInputChars()) {
            throw new InvalidRequestException("AI_INPUT_TOO_LONG",
                    "Please keep it under " + props.maxInputChars() + " characters");
        }
        Request request = new Request(feature, userId, userText.length(), sha256(userText));

        if (!llm.enabled()) {
            return fallback(request, AiOutcome.DISABLED);
        }
        if (options.chargeUserQuota()) {
            RateLimiter.Decision daily = limiter.tryConsume("ai:user-day:" + userId, props.quota().userDaily());
            if (!daily.allowed()) {
                finish(request, AiOutcome.USER_QUOTA, null, null);
                throw new RateLimitedException(daily.retryAfterSeconds());
            }
        }
        CircuitBreaker.State state = circuitBreaker.getState();
        if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) {
            return fallback(request, AiOutcome.CIRCUIT_OPEN);
        }
        if (!limiter.tryConsume("ai:global:day", props.quota().globalDaily()).allowed()
                || !limiter.tryConsume("ai:global:minute", props.quota().globalPerMinute()).allowed()) {
            return fallback(request, AiOutcome.QUOTA);
        }
        return call(request, systemPrompt, userText, options.accept());
    }

    private AiResult call(Request request, String systemPrompt, String userText, Predicate<String> accept) {
        long start = System.nanoTime();
        Callable<LlmClient.LlmReply> timed = timeLimiterOverrides.getOrDefault(request.feature(), timeLimiter).decorateFutureSupplier(
                () -> executor.submit(() -> llm.complete(systemPrompt, userText)));
        try {
            LlmClient.LlmReply reply = circuitBreaker.executeCallable(timed);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            latency.get(request.feature()).record(ms, TimeUnit.MILLISECONDS);
            if (reply == null || reply.text() == null || reply.text().isBlank()) {
                finish(request, AiOutcome.ERROR, ms, reply);
                return new AiResult.Fallback(AiOutcome.ERROR);
            }
            if (!accept.test(reply.text())) {
                finish(request, AiOutcome.INVALID_OUTPUT, ms, reply);
                return new AiResult.Fallback(AiOutcome.INVALID_OUTPUT);
            }
            finish(request, AiOutcome.OK, ms, reply);
            return new AiResult.Answer(reply.text().strip(), llm.model(), ms);
        } catch (CallNotPermittedException e) {        // opened between our state check and the call
            return fallback(request, AiOutcome.CIRCUIT_OPEN);
        } catch (TimeoutException e) {
            return fallback(request, AiOutcome.TIMEOUT, start);
        } catch (RejectedExecutionException e) {
            return fallback(request, AiOutcome.BUSY);
        } catch (Exception e) {
            // Class + message only: the message never contains the prompt, and the key is in a header
            log.warn("AI call failed ({} for user {}): {}", request.feature(), request.userId(), e.toString());
            return fallback(request, AiOutcome.ERROR, start);
        }
    }

    private AiResult fallback(Request request, AiOutcome outcome) {
        finish(request, outcome, null, null);
        return new AiResult.Fallback(outcome);
    }

    private AiResult fallback(Request request, AiOutcome outcome, long startNanos) {
        finish(request, outcome, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), null);
        return new AiResult.Fallback(outcome);
    }

    private void finish(Request request, AiOutcome outcome, Long latencyMs, LlmClient.LlmReply reply) {
        counters.get(request.feature()).get(outcome).increment();
        audit.record(new AiAuditLog.Entry(request.feature(), request.userId(), outcome,
                llm.enabled() ? llm.model() : null, latencyMs, request.inputChars(), request.inputSha256(),
                reply == null ? null : reply.promptTokens(), reply == null ? null : reply.completionTokens()));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record Request(AiFeature feature, long userId, int inputChars, String inputSha256) {}

    /** Lowercase hex SHA-256 of the UTF-8 text (audit rows, cache keys). */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every JDK has SHA-256
        }
    }

    /** Fixed pool + bounded queue + abort: a bulkhead, so slow LLM calls can't take more than N threads. */
    private static ExecutorService newExecutor(int threads, int queue) {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                queue == 0 ? new java.util.concurrent.SynchronousQueue<>() : new ArrayBlockingQueue<>(queue),
                r -> {
                    Thread t = new Thread(r, "ai-call-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
