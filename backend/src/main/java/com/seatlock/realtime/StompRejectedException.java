package com.seatlock.realtime;

import org.springframework.messaging.MessageDeliveryException;

/**
 * A client frame we refuse. The {@code code} becomes the ERROR frame's {@code message} header (same codes
 * as the REST error contract where they overlap); the server then closes the connection.
 * A {@link MessageDeliveryException} so Spring's channel rethrows it unwrapped.
 */
class StompRejectedException extends MessageDeliveryException {

    private final String code;

    StompRejectedException(String code, String description) {
        super(description);
        this.code = code;
    }

    String code() {
        return code;
    }
}
