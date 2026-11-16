package com.seatlock.common.exception;

/** A dependency we need for this request is down (e.g. Redis for seat holds). Mapped to HTTP 503. */
public class ServiceUnavailableException extends SeatLockException {
    public ServiceUnavailableException(String code, String message) {
        super(code, message);
    }
}
