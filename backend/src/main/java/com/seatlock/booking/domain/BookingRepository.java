package com.seatlock.booking.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** Seats fetched in the same query: open-in-view is off, so views are built outside a session. */
    @EntityGraph(attributePaths = "seats")
    Optional<Booking> findByPublicId(UUID publicId);

    @EntityGraph(attributePaths = "seats")
    Optional<Booking> findByHoldId(UUID holdId);
}
