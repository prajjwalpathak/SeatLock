package com.seatlock.catalog.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByIdAndStatus(Long id, EventStatus status);
}