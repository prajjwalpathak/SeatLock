package com.seatlock.catalog.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Venue layout in rendering order (row by row, left to right). */
    List<Seat> findByVenueIdOrderByGridRowAscGridColAsc(Long venueId);
}