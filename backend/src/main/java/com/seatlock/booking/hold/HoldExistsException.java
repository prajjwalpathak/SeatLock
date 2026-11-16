package com.seatlock.booking.hold;

import com.seatlock.common.exception.ConflictException;
import java.util.Map;

/** 409 {@code HOLD_EXISTS}: the user already holds seats for this show. Carries that hold's id. */
public class HoldExistsException extends ConflictException {

    private final String holdId;

    public HoldExistsException(String holdId) {
        super("HOLD_EXISTS", "You already hold seats for this show; release that hold first");
        this.holdId = holdId;
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("holdId", holdId);
    }
}
