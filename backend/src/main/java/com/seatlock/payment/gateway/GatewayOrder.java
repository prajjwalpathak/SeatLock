package com.seatlock.payment.gateway;

/** The order as the gateway created it. The frontend needs {@code orderId} to open the checkout (W4·D2). */
public record GatewayOrder(GatewayName gateway, String orderId, long amountPaise, String currency, String receipt) {
}
