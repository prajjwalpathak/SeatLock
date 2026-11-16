package com.seatlock.common.idempotency;

import java.time.Instant;

/** One stored key. {@code responseStatus}/{@code responseBody} are null while IN_PROGRESS. */
public record IdempotencyRecord(long id, long userId, String key, String endpoint, String requestHash,
                                Status status, Integer responseStatus, String responseBody,
                                Instant lockedAt, Instant expiresAt) {

    public enum Status { IN_PROGRESS, COMPLETED }
}
