package com.seatlock.booking.domain;

/** Lifecycle: HELD -> PAYMENT_PENDING -> CONFIRMED, or EXPIRED / CANCELLED / REFUNDED (State pattern: BookingState). */
public enum BookingStatus { HELD, PAYMENT_PENDING, CONFIRMED, EXPIRED, CANCELLED, REFUNDED }