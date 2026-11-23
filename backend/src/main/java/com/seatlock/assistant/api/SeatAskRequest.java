package com.seatlock.assistant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Free-text seat request, e.g. "2 together near the aisle under ₹800". Same 300-character cap as every AI input. */
public record SeatAskRequest(@NotBlank @Size(max = 300) String text) {}
