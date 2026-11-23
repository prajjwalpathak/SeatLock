package com.seatlock.assistant.ai;

/**
 * Result of {@link AiGateway#complete}: either the model's {@link Answer} or a {@link Fallback} with the reason.
 * Callers switch over it; there is no exception path for "AI didn't work" (AI is never required).
 */
public sealed interface AiResult {

    AiOutcome outcome();

    record Answer(String text, String model, long latencyMs) implements AiResult {
        @Override
        public AiOutcome outcome() {
            return AiOutcome.OK;
        }
    }

    record Fallback(AiOutcome outcome) implements AiResult {
        public Fallback {
            if (outcome == null || outcome == AiOutcome.OK) {
                throw new IllegalArgumentException("A fallback needs a non-OK outcome");
            }
        }
    }
}
