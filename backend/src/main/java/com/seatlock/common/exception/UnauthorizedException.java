package com.seatlock.common.exception;

/** The caller couldn't be authenticated for a business reason (e.g. a used sign-in code). Mapped to HTTP 401. */
public class UnauthorizedException extends SeatLockException {

    public UnauthorizedException(String code, String message) {
        super(code, message);
    }
}
