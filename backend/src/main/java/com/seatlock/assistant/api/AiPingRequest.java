package com.seatlock.assistant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/admin/ai/ping}. Same 300-character cap as every AI input. */
public record AiPingRequest(@NotBlank @Size(max = 300) String message) {}
