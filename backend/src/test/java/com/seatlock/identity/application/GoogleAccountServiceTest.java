package com.seatlock.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.identity.application.GoogleAccountService.GoogleSignInRejectedException;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import testsupport.IntegrationTest;

/** Linking rule B1 against the real database. */
@IntegrationTest
class GoogleAccountServiceTest {

    @Autowired GoogleAccountService google;
    @Autowired UserService userService;
    @Autowired AuthService authService;

    @Test
    void newVerifiedEmailCreatesGoogleUserWithoutPassword() {
        String email = "g-" + UUID.randomUUID() + "@gmail.com";

        User user = google.signIn(email, true, "Asha Rao");

        assertThat(user.getId()).isNotNull();
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getDisplayName()).isEqualTo("Asha Rao");
        assertThat(google.signIn(email.toUpperCase(), true, "Asha Rao").getId()).isEqualTo(user.getId()); // same account next time
    }

    @Test
    void verifiedEmailOfExistingPasswordAccountSignsIntoThatAccountAndKeepsItsPassword() {
        String email = "linked-" + UUID.randomUUID() + "@gmail.com";
        User local = userService.register(email, "correct-horse-battery", "Local First");

        User viaGoogle = google.signIn(email.toUpperCase(), true, "Different Name");

        assertThat(viaGoogle.getId()).isEqualTo(local.getId());
        assertThat(viaGoogle.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(viaGoogle.getDisplayName()).isEqualTo("Local First");
        assertThat(authService.login(email, "correct-horse-battery").value()).isNotBlank(); // password still works
    }

    @Test
    void unverifiedOrMissingEmailIsRefused() {
        String email = "unverified-" + UUID.randomUUID() + "@gmail.com";
        userService.register(email, "correct-horse-battery", "Victim");

        // someone controlling an unverified Google account with the victim's address gets nothing
        assertThatThrownBy(() -> google.signIn(email, false, "Attacker"))
                .isInstanceOf(GoogleSignInRejectedException.class)
                .extracting("code").isEqualTo("GOOGLE_EMAIL_NOT_VERIFIED");
        assertThatThrownBy(() -> google.signIn(email, null, "Attacker"))
                .extracting("code").isEqualTo("GOOGLE_EMAIL_NOT_VERIFIED");
        assertThatThrownBy(() -> google.signIn(null, true, "No Email"))
                .extracting("code").isEqualTo("GOOGLE_NO_EMAIL");
    }

    @Test
    void displayNameFallsBackToEmailPrefixAndIsCapped() {
        assertThat(GoogleAccountService.displayName(null, "asha.rao@gmail.com")).isEqualTo("asha.rao");
        assertThat(GoogleAccountService.displayName("  ", "asha.rao@gmail.com")).isEqualTo("asha.rao");
        assertThat(GoogleAccountService.displayName("x".repeat(150), "a@b.com")).hasSize(100);
    }
}
