package com.seatlock.booking.domain;

/** Lifecycle: HELD -> PAYMENT_PENDING -> CONFIRMED, or EXPIRED / CANCELLED / REFUNDED (state machine on W3·D4). */
public enum BookingStatus { HELD, PAYMENT_PENDING, CONFIRMED, EXPIRED, CANCELLED, REFUNDED }