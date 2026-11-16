package com.seatlock.booking.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** Seats fetched in the same query: open-in-view is off, so views are built outside a session. */
    @EntityGraph(attributePaths = "seats")
    Optional<Booking> findByPublicId(UUID publicId);

    @EntityGraph(attributePaths = "seats")
    Optional<Booking> findByHoldId(UUID holdId);

    /**
     * Locks up to {@code limit} unpaid bookings whose time is up. SKIP LOCKED: rows another transaction holds
     * (a second sweeper, a payment confirmation) are skipped instead of waited for, so parallel sweepers
     * split the work and never block each other. Must run inside a transaction (the locks last until commit).
     */
    @Query(value = """
            SELECT id FROM bookings
            WHERE status IN ('HELD', 'PAYMENT_PENDING') AND expires_at <= :now
            ORDER BY expires_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockExpiredIds(@Param("now") Instant now, @Param("limit") int limit);

    @EntityGraph(attributePaths = "seats")
    List<Booking> findAllWithSeatsByIdIn(Collection<Long> ids);

    /**
     * Row-locked read (SELECT … FOR UPDATE) for user actions like cancel: waits for a concurrent sweeper or
     * payment to commit, then sees the final status. No entity graph: Postgres rejects FOR UPDATE on the
     * outer-joined side, so seats load lazily inside the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.publicId = :publicId")
    Optional<Booking> findForUpdateByPublicId(@Param("publicId") UUID publicId);
}
