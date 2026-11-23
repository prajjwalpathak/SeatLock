package com.seatlock.assistant.ai;

/**
 * Port to the language model: one system + user message in, text out. {@link GeminiLlmClient} is the Spring AI
 * adapter; tests use stubs. Only this port touches Spring AI types, so guardrails are testable without a model.
 */
public interface LlmClient {

    /** Blocking call; may throw anything (the gateway maps it to an outcome). */
    LlmReply complete(String systemPrompt, String userText);

    /** {@code false} when no model is configured: the gateway answers DISABLED without calling. */
    boolean enabled();

    /** Model id for audit rows and metrics, e.g. {@code gemini-3.5-flash-lite}. */
    String model();

    /** Token counts may be null when the provider doesn't report them. */
    record LlmReply(String text, Integer promptTokens, Integer completionTokens) {}

    /** Used when {@code spring.ai.model.chat=none}. */
    LlmClient DISABLED = new LlmClient() {
        @Override
        public LlmReply complete(String systemPrompt, String userText) {
            throw new IllegalStateException("AI is disabled");
        }

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String model() {
            return "none";
        }
    };
}
