package com.seatlock.common.idempotency;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code seatlock.idempotency.*}.
 * @param ttl   how long a key's stored response is replayed (then the row is deleted)
 * @param lease how long an IN_PROGRESS claim is protected before a retry may take it over (crash recovery)
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.idempotency")
public record IdempotencyProperties(@NotNull Duration ttl, @NotNull Duration lease) {
}
