package com.seatlock.assistant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A question about cancelling one booking, e.g. "Can I cancel?". Same 300-character cap as every AI input. */
public record RefundQuestionRequest(@NotBlank @Size(max = 300) String question) {}
