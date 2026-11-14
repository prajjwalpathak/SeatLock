package com.seatlock.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatlock.common.config.FrontendProperties;
import com.seatlock.identity.application.GoogleAccountService;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.test.util.ReflectionTestUtils;

class GoogleLoginHandlersTest {

    final GoogleAccountService accounts = mock(GoogleAccountService.class);
    final OneTimeCodeService codes = mock(OneTimeCodeService.class);
    final GoogleLoginHandlers handlers =
            new GoogleLoginHandlers(accounts, codes, new FrontendProperties("http://localhost:5173"));

    @Test
    void successRedirectsToSpaCallbackWithOneTimeCodeNeverTheJwt() throws Exception {
        User user = new User("asha@gmail.com", null, "Asha", Role.USER, AuthProvider.GOOGLE);
        ReflectionTestUtils.setField(user, "id", 42L);
        when(accounts.signIn("asha@gmail.com", true, "Asha Rao")).thenReturn(user);
        when(codes.issue(42L)).thenReturn("one-time-code");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.onAuthenticationSuccess(new MockHttpServletRequest(), response, google("asha@gmail.com", true));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/auth/callback?code=one-time-code");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void refusedSignInRedirectsToLoginWithTheReasonCode() throws Exception {
        when(accounts.signIn(any(), any(), any())).thenThrow(rejected("GOOGLE_EMAIL_NOT_VERIFIED"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.onAuthenticationSuccess(new MockHttpServletRequest(), response, google("x@gmail.com", false));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/login?error=GOOGLE_EMAIL_NOT_VERIFIED");
        verify(codes, never()).issue(anyLong());
    }

    @Test
    void nonOidcPrincipalIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.onAuthenticationSuccess(new MockHttpServletRequest(), response, new TestingAuthenticationToken("u", "p"));
        assertThat(response.getRedirectedUrl()).endsWith("/login?error=GOOGLE_SIGN_IN_FAILED");
    }

    @Test
    void failureRedirectsToLoginWithGenericCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/login?error=GOOGLE_SIGN_IN_FAILED");
    }

    private static TestingAuthenticationToken google(String email, boolean verified) {
        OidcIdToken idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", "google-123", "email", email, "email_verified", verified, "name", "Asha Rao"));
        DefaultOidcUser oidcUser = new DefaultOidcUser(List.of(new OidcUserAuthority(idToken)), idToken);
        return new TestingAuthenticationToken(oidcUser, null, List.of());
    }

    private static GoogleAccountService.GoogleSignInRejectedException rejected(String code) {
        return new GoogleAccountService.GoogleSignInRejectedException(code, "refused");
    }
}
