package com.seatlock.common.exception;

/** The request conflicts with current state (e.g. seat already held). Mapped to HTTP 409. */
public class ConflictException extends SeatLockException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}