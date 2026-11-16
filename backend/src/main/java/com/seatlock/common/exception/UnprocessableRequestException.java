package com.seatlock.common.exception;

/** Well-formed but can't be processed as sent (e.g. an idempotency key reused for another request). HTTP 422. */
public class UnprocessableRequestException extends SeatLockException {
    public UnprocessableRequestException(String code, String message) {
        super(code, message);
    }
}
