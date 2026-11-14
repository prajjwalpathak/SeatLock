package com.seatlock.identity.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatlock.common.exception.ConflictException;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Paths the integration tests can't reach reliably (a real race between two sign-ups). */
class UserServiceTest {

    final UserRepository users = mock(UserRepository.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);
    final UserService service = new UserService(users, encoder);

    @Test
    void raceLostOnUniqueEmailIndexBecomesEmailTaken() {
        when(users.existsByEmail("race@example.com")).thenReturn(false); // pre-check passes...
        when(encoder.encode(any())).thenReturn("{bcrypt}hash");
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("insert failed",
                new SQLException("duplicate key value violates unique constraint \"uq_users_email\""))); // ...DB says no

        assertThatThrownBy(() -> service.register("Race@Example.com", "password-123", "Racer"))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void otherIntegrityViolationsAreNotMisreportedAsEmailTaken() {
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("{bcrypt}hash");
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("insert failed",
                new SQLException("violates check constraint \"ck_users_role\"")));

        assertThatThrownBy(() -> service.register("x@example.com", "password-123", "X"))
                .isInstanceOf(DataIntegrityViolationException.class); // → 500, logged with stack trace
    }

    @Test
    void tooLongPasswordIsRejectedBeforeHashing() {
        assertThatThrownBy(() -> service.register("x@example.com", "\u00e9".repeat(40), "X")) // 40 x "é": 40 chars, 80 bytes
                .extracting("code").isEqualTo("PASSWORD_TOO_LONG");
        verify(encoder, never()).encode(any());
    }
}
