package com.seatlock.payment.application;

import com.seatlock.payment.domain.PaymentStatus;
import com.seatlock.payment.gateway.GatewayName;
import java.time.Instant;
import java.util.UUID;

/**
 * The open payment order for a booking, as the frontend needs it to open the checkout (W4·D2).
 * {@code payBy} is the booking's deadline; a payment after it is refunded (W4·D4).
 */
public record PaymentOrderView(UUID bookingId, int attempt, GatewayName gateway, String orderId,
                               long amountPaise, String currency, PaymentStatus status, Instant payBy) {
}
