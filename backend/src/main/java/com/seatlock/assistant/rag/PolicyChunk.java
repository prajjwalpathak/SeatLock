package com.seatlock.assistant.rag;

/** One "## " section of a policy file. {@code text} = what is embedded and shown: title, heading and body. */
public record PolicyChunk(String doc, int index, String heading, String text) {}
