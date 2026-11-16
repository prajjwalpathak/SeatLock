package com.seatlock.booking.hold;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code seatlock.holds.*}: hold length and seats per hold. Bad values fail at startup. */
@Validated
@ConfigurationProperties(prefix = "seatlock.holds")
public record HoldProperties(@NotNull Duration ttl, @Min(1) @Max(10) int maxSeats) {
}
