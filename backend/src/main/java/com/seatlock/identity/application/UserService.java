package com.seatlock.identity.application;

import com.seatlock.common.exception.ConflictException;
import com.seatlock.common.exception.InvalidRequestException;
import com.seatlock.common.exception.NotFoundException;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account management. Public entry point of the identity module for other modules. */
@Service
public class UserService {

    /** BCrypt only uses the first 72 bytes; longer input would be silently truncated (or rejected). */
    static final int BCRYPT_MAX_BYTES = 72;

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** New local account with role USER. 409 EMAIL_TAKEN if the e-mail exists (any letter case). */
    @Transactional
    public User register(String email, String rawPassword, String displayName) {
        String normalizedEmail = User.normalizeEmail(email);
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new InvalidRequestException("PASSWORD_TOO_LONG",
                    "Password must be at most " + BCRYPT_MAX_BYTES + " bytes (fewer if it uses emoji or non-Latin letters).");
        }
        if (users.existsByEmail(normalizedEmail)) {
            throw emailTaken();
        }
        User user = new User(normalizedEmail, passwordEncoder.encode(rawPassword), displayName.trim(),
                Role.USER, AuthProvider.LOCAL);
        try {
            user = users.saveAndFlush(user); // flush now so a race hits the unique index inside this try
        } catch (DataIntegrityViolationException ex) {
            // Two sign-ups with the same e-mail at the same moment: the DB constraint is the real guard.
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains("uq_users_email")) {
                throw emailTaken();
            }
            throw ex;
        }
        log.info("Registered user id={}", user.getId()); // id only: no e-mail (PII) in logs
        return user;
    }

    @Transactional(readOnly = true)
    public User getById(long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    private static ConflictException emailTaken() {
        return new ConflictException("EMAIL_TAKEN", "An account with this e-mail already exists.");
    }
}
