package com.seatlock.assistant.api;

import com.seatlock.assistant.ai.AiOutcome;

/** {@code answer}, {@code model} and {@code latencyMs} are null unless {@code outcome} is OK. */
public record AiPingResponse(AiOutcome outcome, String answer, String model, Long latencyMs) {}
