package com.seatlock.assistant.ai;

import com.seatlock.common.ratelimit.RateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the AI guardrails. A {@link ChatModel} exists only when {@code spring.ai.model.chat=google-genai}
 * ({@code SEATLOCK_AI_PROVIDER}); without one the gateway runs with {@link LlmClient#DISABLED}, so every AI
 * feature uses its fallback and the app still starts (CI, tests, prod without a key). The state is logged at startup.
 * Circuit breaker and time limiter "gemini" come from {@code resilience4j.*} in application.yml.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    static final String RESILIENCE_NAME = "gemini";
    /** Longer time limit for answers someone reads at leisure (Refund Assistant). */
    static final String EXPLAIN_TIME_LIMIT = "gemini-explain";

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    LlmClient llmClient(ObjectProvider<ChatModel> chatModel, AiProperties props) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            log.info("AI DISABLED (spring.ai.model.chat=none): AI features use their fallbacks");
            return LlmClient.DISABLED;
        }
        log.info("AI enabled: {} via {}", props.model(), model.getClass().getSimpleName());
        return new GeminiLlmClient(model, props.model());
    }

    @Bean
    AiGateway aiGateway(LlmClient llm, AiProperties props, RateLimiter limiter,
                        CircuitBreakerRegistry circuitBreakers, TimeLimiterRegistry timeLimiters,
                        AiAuditLog audit, MeterRegistry meters) {
        return new AiGateway(llm, props, limiter, circuitBreakers.circuitBreaker(RESILIENCE_NAME),
                timeLimiters.timeLimiter(RESILIENCE_NAME),
                java.util.Map.of(AiFeature.REFUND_ASSISTANT, timeLimiters.timeLimiter(EXPLAIN_TIME_LIMIT)),
                audit, meters);
    }
}
