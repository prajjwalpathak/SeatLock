package com.seatlock.identity.application;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps a Google identity to one of our accounts (ADR-003, option B1):
 * <ul>
 *   <li>Google must say the e-mail is verified, otherwise sign-in is refused.</li>
 *   <li>An existing account with that e-mail (any provider) is signed into: Google proved the
 *       person controls the address. Its role and password stay as they are.</li>
 *   <li>Otherwise a new USER is created with provider GOOGLE and no password.</li>
 * </ul>
 */
@Service
public class GoogleAccountService {

    /** Why a Google sign-in was refused; the code is shown to the SPA. */
    public static class GoogleSignInRejectedException extends RuntimeException {
        private final String code;

        public GoogleSignInRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    static final int MAX_DISPLAY_NAME = 100;

    private static final Logger log = LoggerFactory.getLogger(GoogleAccountService.class);

    private final UserRepository users;

    public GoogleAccountService(UserRepository users) {
        this.users = users;
    }

    /** No surrounding transaction: on a sign-up race the failed insert must not poison the re-read. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public User signIn(String email, Boolean emailVerified, String name) {
        if (email == null || email.isBlank()) {
            throw new GoogleSignInRejectedException("GOOGLE_NO_EMAIL", "Google did not share an e-mail address");
        }
        if (!Boolean.TRUE.equals(emailVerified)) {
            throw new GoogleSignInRejectedException("GOOGLE_EMAIL_NOT_VERIFIED", "Google e-mail is not verified");
        }
        String normalized = User.normalizeEmail(email);
        return users.findByEmail(normalized).orElseGet(() -> create(normalized, name));
    }

    private User create(String email, String name) {
        try {
            User user = users.saveAndFlush(new User(email, null, displayName(name, email), Role.USER, AuthProvider.GOOGLE));
            log.info("Registered user id={} via Google", user.getId());
            return user;
        } catch (DataIntegrityViolationException ex) {
            // Same person signing in twice at once: the other request created it first
            return users.findByEmail(email).orElseThrow(() -> ex);
        }
    }

    static String displayName(String name, String email) {
        String value = (name == null || name.isBlank()) ? email.substring(0, email.indexOf('@')) : name.trim();
        return value.length() > MAX_DISPLAY_NAME ? value.substring(0, MAX_DISPLAY_NAME) : value;
    }
}
