package com.seatlock.common.system;

import java.time.Instant;

/**
 * Server's current time. {@code epochMillis} lets clients compute their clock offset
 * precisely (offset = epochMillis - clientNowMillis, adjusted for half the round-trip).
 */
public record ServerTimeResponse(Instant serverTime, long epochMillis) {}