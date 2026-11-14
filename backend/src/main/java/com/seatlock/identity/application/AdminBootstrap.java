package com.seatlock.identity.application;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the ADMIN account at startup from environment variables, so admin credentials never
 * live in Git or in a migration. Create-only and idempotent: safe on every restart and when
 * two instances start at once.
 */
@Component
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrap implements ApplicationRunner {

    static final int MIN_ADMIN_PASSWORD_LENGTH = 12;

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminBootstrapProperties properties;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(AdminBootstrapProperties properties, UserRepository users,
                          PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean hasEmail = hasText(properties.email());
        boolean hasPassword = hasText(properties.password());
        if (!hasEmail && !hasPassword) {
            log.info("Admin bootstrap skipped (SEATLOCK_ADMIN_EMAIL / SEATLOCK_ADMIN_PASSWORD not set)");
            return;
        }
        if (hasEmail != hasPassword) {
            throw new IllegalStateException("Set both SEATLOCK_ADMIN_EMAIL and SEATLOCK_ADMIN_PASSWORD, or neither");
        }
        String password = properties.password();
        if (password.length() < MIN_ADMIN_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > UserService.BCRYPT_MAX_BYTES) {
            throw new IllegalStateException("SEATLOCK_ADMIN_PASSWORD must be " + MIN_ADMIN_PASSWORD_LENGTH
                    + " characters to " + UserService.BCRYPT_MAX_BYTES + " bytes");
        }
        ensureAdmin(User.normalizeEmail(properties.email()), password);
    }

    private void ensureAdmin(String email, String password) {
        var existing = users.findByEmail(email);
        if (existing.isPresent()) {
            if (existing.get().getRole() == Role.ADMIN) {
                log.info("Admin account already exists (id={}); left unchanged", existing.get().getId());
            } else {
                // Never promote: whoever registered this e-mail first would silently become admin.
                log.warn("SEATLOCK_ADMIN_EMAIL belongs to a non-admin account (id={}); NOT promoting it. "
                        + "Use a different admin e-mail.", existing.get().getId());
            }
            return;
        }
        try {
            User admin = users.saveAndFlush(new User(email, passwordEncoder.encode(password), "Administrator",
                    Role.ADMIN, AuthProvider.LOCAL));
            log.info("Admin account created (id={})", admin.getId());
        } catch (DataIntegrityViolationException ex) {
            log.info("Admin account was created concurrently by another instance");
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
