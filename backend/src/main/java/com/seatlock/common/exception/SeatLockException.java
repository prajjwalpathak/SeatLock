package com.seatlock.common.exception;

import java.util.Map;

/**
 * Base class for expected business errors. {@code code} is a stable, machine-readable
 * identifier (e.g. "SEAT_ALREADY_HELD") that clients can switch on.
 */
public abstract class SeatLockException extends RuntimeException {

    private final String code;

    protected SeatLockException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** Extra machine-readable fields copied into the ProblemDetail body (none by default). */
    public Map<String, Object> getProperties() {
        return Map.of();
    }
}