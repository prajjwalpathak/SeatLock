package com.seatlock.common.exception;

/** The request is syntactically valid but semantically wrong (e.g. a tampered cursor). Mapped to HTTP 400. */
public class InvalidRequestException extends SeatLockException {

    public InvalidRequestException(String code, String message) {
        super(code, message);
    }
}