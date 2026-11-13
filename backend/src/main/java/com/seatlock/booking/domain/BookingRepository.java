package com.seatlock.booking.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByPublicId(UUID publicId);

    Optional<Booking> findByHoldId(UUID holdId);
}