package com.seatlock.payment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    boolean existsByGatewayPaymentIdAndReason(String gatewayPaymentId, RefundReason reason);

    Optional<Refund> findByGatewayPaymentIdAndReason(String gatewayPaymentId, RefundReason reason);

    List<Refund> findByPaymentIdOrderByIdAsc(Long paymentId);

    /**
     * Locks up to {@code limit} due PENDING refunds, skipping rows another worker holds (two instances never send
     * the same refund at the same time). Must run in a transaction.
     */
    @Query(value = """
            SELECT id FROM refunds
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockDueIds(@Param("now") Instant now, @Param("limit") int limit);
}
