package com.seatlock.identity.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Emails are stored lower-case; callers must pass a lower-cased email. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}