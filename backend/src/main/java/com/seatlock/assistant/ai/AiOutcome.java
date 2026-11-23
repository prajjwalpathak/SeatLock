package com.seatlock.assistant.ai;

/**
 * How one AI request ended. {@link #OK} is the only outcome with a model answer; every other one means the
 * feature uses its non-AI fallback (or, for {@link #USER_QUOTA}, the caller gets 429).
 */
public enum AiOutcome {
    /** The model answered. */
    OK,
    /** No model configured ({@code spring.ai.model.chat=none}). */
    DISABLED,
    /** This user spent their daily AI quota → 429 RATE_LIMITED. */
    USER_QUOTA,
    /** The global per-minute or daily cap (kept under Google's free-tier limits) is spent. */
    QUOTA,
    /** Circuit breaker open: recent calls failed, so we don't even try. */
    CIRCUIT_OPEN,
    /** No answer within the time limit. */
    TIMEOUT,
    /** All AI threads busy and the queue full on this instance. */
    BUSY,
    /** The call failed (provider error, quota on Google's side, empty answer). */
    ERROR,
    /** The model answered, but the answer failed our validation (used from W7·D3). */
    INVALID_OUTPUT;

    public boolean isFallback() {
        return this != OK;
    }
}
