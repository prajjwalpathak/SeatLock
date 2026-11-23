package com.seatlock.common.exception;

/** Request body over the configured limit (RequestBodyLimitFilter) → 413 PAYLOAD_TOO_LARGE. */
public class PayloadTooLargeException extends SeatLockException {

    public PayloadTooLargeException(long limitBytes) {
        super("PAYLOAD_TOO_LARGE", "Request body is larger than " + limitBytes + " bytes.");
    }
}
