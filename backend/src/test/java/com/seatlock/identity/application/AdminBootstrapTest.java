package com.seatlock.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminBootstrapTest {

    static final String EMAIL = "Admin@SeatLock.test";
    static final String PASSWORD = "a-strong-admin-pass";

    final UserRepository users = mock(UserRepository.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);

    @Test
    void createsAdminWhenMissing() {
        when(users.findByEmail("admin@seatlock.test")).thenReturn(Optional.empty());
        when(encoder.encode(PASSWORD)).thenReturn("{bcrypt}hash");
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        bootstrap(EMAIL, PASSWORD).run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@seatlock.test");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}hash");
    }

    @Test
    void existingAdminIsLeftUnchanged() {
        when(users.findByEmail("admin@seatlock.test")).thenReturn(Optional.of(user(Role.ADMIN)));

        bootstrap(EMAIL, PASSWORD).run(null);

        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(encoder);
    }

    @Test
    void existingNonAdminAccountIsNeverPromoted() {
        User squatter = user(Role.USER); // someone registered the admin e-mail first
        when(users.findByEmail("admin@seatlock.test")).thenReturn(Optional.of(squatter));

        bootstrap(EMAIL, PASSWORD).run(null);

        verify(users, never()).saveAndFlush(any());
        assertThat(squatter.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void concurrentCreationByAnotherInstanceIsNotAnError() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(encoder.encode(any())).thenReturn("{bcrypt}hash");
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatCode(() -> bootstrap(EMAIL, PASSWORD).run(null)).doesNotThrowAnyException();
    }

    @Test
    void skippedWhenNotConfigured() {
        bootstrap("", null).run(null);
        verifyNoInteractions(users, encoder);
    }

    @Test
    void halfConfiguredOrWeakPasswordFailsStartup() {
        assertThatThrownBy(() -> bootstrap(EMAIL, "").run(null)).hasMessageContaining("both");
        assertThatThrownBy(() -> bootstrap(EMAIL, "short-pass").run(null)).hasMessageContaining("12 characters");
        verifyNoInteractions(users);
    }

    private AdminBootstrap bootstrap(String email, String password) {
        return new AdminBootstrap(new AdminBootstrapProperties(email, password), users, encoder);
    }

    private static User user(Role role) {
        return new User("admin@seatlock.test", "{bcrypt}x", "Someone", role, AuthProvider.LOCAL);
    }
}
