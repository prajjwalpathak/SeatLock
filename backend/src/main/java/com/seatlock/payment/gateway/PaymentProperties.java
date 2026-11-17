package com.seatlock.payment.gateway;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code seatlock.payments.*}. {@code gateway}: {@code mock} (default) or {@code razorpay}. Bad values fail at startup. */
@Validated
@ConfigurationProperties(prefix = "seatlock.payments")
public record PaymentProperties(@NotNull GatewayName gateway) {
}
