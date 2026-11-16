package com.seatlock.booking.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
