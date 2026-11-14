package com.seatlock.identity.oauth;

import com.seatlock.common.config.FrontendProperties;
import com.seatlock.identity.application.GoogleAccountService;
import com.seatlock.identity.application.GoogleAccountService.GoogleSignInRejectedException;
import com.seatlock.identity.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * What happens when Google sends the browser back:
 * success → find/link/create the user, issue a one-time code, redirect to {frontend}/auth/callback?code=…;
 * failure → redirect to {frontend}/login?error=CODE. The browser never sees our JWT in a URL.
 */
@Component
@EnableConfigurationProperties(FrontendProperties.class)
public class GoogleLoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    static final String CALLBACK_PATH = "/auth/callback";
    static final String LOGIN_PATH = "/login";
    static final String GENERIC_ERROR = "GOOGLE_SIGN_IN_FAILED";

    private static final Logger log = LoggerFactory.getLogger(GoogleLoginHandlers.class);

    private final GoogleAccountService accounts;
    private final OneTimeCodeService codes;
    private final FrontendProperties frontend;

    public GoogleLoginHandlers(GoogleAccountService accounts, OneTimeCodeService codes, FrontendProperties frontend) {
        this.accounts = accounts;
        this.codes = codes;
        this.frontend = frontend;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        SecurityContextHolder.clearContext(); // Google's login is only a step; our JWT is the session
        if (!(authentication.getPrincipal() instanceof OidcUser google)) {
            redirectToLogin(response, GENERIC_ERROR); // only OIDC providers (Google) are registered
            return;
        }
        try {
            User user = accounts.signIn(google.getEmail(), google.getEmailVerified(), google.getFullName());
            String target = UriComponentsBuilder.fromUriString(frontend.baseUrl()).path(CALLBACK_PATH)
                    .queryParam("code", codes.issue(user.getId()))
                    .build().toUriString();
            response.setHeader("Referrer-Policy", "no-referrer"); // don't leak the code to other sites
            response.sendRedirect(target);
        } catch (GoogleSignInRejectedException ex) {
            log.info("Google sign-in refused: {}", ex.getCode()); // no e-mail in logs
            redirectToLogin(response, ex.getCode());
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        // e.g. user pressed "Cancel" at Google, state/cookie mismatch, expired request, bad code
        log.info("Google sign-in failed: {}", exception.getClass().getSimpleName());
        redirectToLogin(response, GENERIC_ERROR);
    }

    private void redirectToLogin(HttpServletResponse response, String errorCode) throws IOException {
        response.sendRedirect(UriComponentsBuilder.fromUriString(frontend.baseUrl()).path(LOGIN_PATH)
                .queryParam("error", errorCode).build().toUriString());
    }
}
