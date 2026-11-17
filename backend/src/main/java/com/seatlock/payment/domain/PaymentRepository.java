package com.seatlock.payment.domain;

import com.seatlock.payment.gateway.GatewayName;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** The open or successful attempt, if any (at most one: ux_payments_live_booking). */
    Optional<Payment> findFirstByBookingIdAndStatusIn(Long bookingId, Collection<PaymentStatus> statuses);

    /** Latest attempt; the next attempt number is this + 1. */
    Optional<Payment> findTopByBookingIdOrderByAttemptDesc(Long bookingId);

    List<Payment> findByBookingIdOrderByAttemptAsc(Long bookingId);

    /** Webhook / checkout callback lookup (W4·D3). */
    Optional<Payment> findByGatewayAndGatewayOrderId(GatewayName gateway, String gatewayOrderId);
}
