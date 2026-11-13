package com.seatlock.catalog.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<Show, Long> {

    List<Show> findByEventIdOrderByStartsAtAsc(Long eventId);
}