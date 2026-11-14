package com.seatlock.identity.security;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Hashes are stored as "{bcrypt}$2a$10$...". The {id} prefix says which algorithm made the hash,
 * so we can move to a stronger one later and still verify old hashes.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    static final String BCRYPT = "bcrypt";

    @Bean
    PasswordEncoder passwordEncoder() {
        // Only BCrypt is accepted (no "{noop}" plain text). Strength 10 ≈ 50–100 ms per hash.
        DelegatingPasswordEncoder encoder =
                new DelegatingPasswordEncoder(BCRYPT, Map.of(BCRYPT, new BCryptPasswordEncoder()));
        // Hashes without a known {id} (e.g. the seed's locked '!') never match instead of throwing.
        encoder.setDefaultPasswordEncoderForMatches(new NeverMatchingEncoder());
        return encoder;
    }

    /** Verifies nothing: used for locked/unknown hashes. Never used to encode. */
    private static final class NeverMatchingEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            throw new UnsupportedOperationException("never used for encoding");
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return false;
        }
    }
}
