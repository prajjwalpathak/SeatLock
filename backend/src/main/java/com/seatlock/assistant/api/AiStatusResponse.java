package com.seatlock.assistant.api;

/** {@code circuit}: CLOSED, OPEN, HALF_OPEN (Resilience4j state of the "gemini" breaker). */
public record AiStatusResponse(boolean enabled, String model, String circuit) {}
