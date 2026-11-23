package com.seatlock.assistant.api;

/**
 * {@code circuit}: CLOSED, OPEN, HALF_OPEN (Resilience4j state of the "gemini" breaker).
 * {@code embeddingModel}: "none" when RAG is off; {@code policyChunks}: indexed sections for that model.
 */
public record AiStatusResponse(boolean enabled, String model, String circuit, String embeddingModel, long policyChunks) {}
