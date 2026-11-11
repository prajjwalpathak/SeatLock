package com.seatlock.common.exception;

/** A requested resource does not exist. Mapped to HTTP 404. */
public class NotFoundException extends SeatLockException {

    public NotFoundException(String resource, Object id) {
        super("NOT_FOUND", "%s %s was not found".formatted(resource, id));
    }
}