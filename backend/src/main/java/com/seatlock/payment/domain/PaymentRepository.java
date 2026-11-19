package com.seatlock.payment.domain;

import com.seatlock.payment.gateway.GatewayName;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** The open or successful attempt, if any (at most one: ux_payments_live_booking). */
    Optional<Payment> findFirstByBookingIdAndStatusIn(Long bookingId, Collection<PaymentStatus> statuses);

    /** The booking's captured payment, row-locked (paid cancellation; same lock order as capture: payment first). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.bookingId = :bookingId and p.status = :status")
    Optional<Payment> findForUpdateByBookingIdAndStatus(@Param("bookingId") Long bookingId,
                                                        @Param("status") PaymentStatus status);

    /** Latest attempt; the next attempt number is this + 1. */
    Optional<Payment> findTopByBookingIdOrderByAttemptDesc(Long bookingId);

    List<Payment> findByBookingIdOrderByAttemptAsc(Long bookingId);

    /**
     * Existence check that loads no entity. Used before {@link #findForUpdateByGatewayOrder} in the same transaction:
     * an entity loaded without the lock would clash with the newer version the locked query returns (FAILURES F5).
     */
    boolean existsByGatewayAndGatewayOrderId(GatewayName gateway, String gatewayOrderId);

    /** Webhook / checkout callback lookup (W4·D3). */
    Optional<Payment> findByGatewayAndGatewayOrderId(GatewayName gateway, String gatewayOrderId);

    /**
     * Same lookup with a row lock: the checkout callback and the webhook for one payment arrive at about the same
     * time; locking the payment first serializes them (then the booking row is locked).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.gateway = :gateway and p.gatewayOrderId = :orderId")
    Optional<Payment> findForUpdateByGatewayOrder(@Param("gateway") GatewayName gateway, @Param("orderId") String orderId);
}
