package com.seatlock.assistant.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin retrieval check / recall eval: a question and how many chunks to return (default 4). */
public record PolicySearchRequest(@NotBlank @Size(max = 300) String question, @Min(1) @Max(10) Integer k) {}
