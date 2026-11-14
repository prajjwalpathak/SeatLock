package com.seatlock.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordConfigTest {

    final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

    @Test
    void hashesWithBcryptPrefixAndSaltsEveryHash() {
        String first = encoder.encode("correct horse battery");
        String second = encoder.encode("correct horse battery");

        assertThat(first).startsWith("{bcrypt}$2a$10$").hasSizeLessThanOrEqualTo(100); // fits the column
        assertThat(first).isNotEqualTo(second);                                          // random salt
        assertThat(encoder.matches("correct horse battery", first)).isTrue();
        assertThat(encoder.matches("wrong", first)).isFalse();
    }

    @Test
    void lockedOrPlainTextHashesNeverMatch() {
        assertThat(encoder.matches("anything", "!")).isFalse();               // seed's locked accounts
        assertThat(encoder.matches("secret", "{noop}secret")).isFalse();      // plain text not accepted
    }
}
