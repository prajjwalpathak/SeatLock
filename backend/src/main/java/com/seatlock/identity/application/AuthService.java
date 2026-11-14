package com.seatlock.identity.application;

import com.seatlock.common.exception.NotFoundException;
import com.seatlock.common.exception.UnauthorizedException;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.oauth.OneTimeCodeService;
import com.seatlock.identity.security.AccessToken;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.nio.charset.StandardCharsets;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** E-mail + password, or a Google one-time code → access token. */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final OneTimeCodeService oneTimeCodes;
    private final UserService userService;

    public AuthService(AuthenticationManager authenticationManager, TokenService tokenService,
                       OneTimeCodeService oneTimeCodes, UserService userService) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.oneTimeCodes = oneTimeCodes;
        this.userService = userService;
    }

    /** @throws BadCredentialsException for any failure (→ 401 INVALID_CREDENTIALS) */
    public AccessToken login(String email, String rawPassword) {
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > UserService.BCRYPT_MAX_BYTES) {
            throw new BadCredentialsException("password longer than BCrypt can check"); // no account has one
        }
        Authentication result = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(User.normalizeEmail(email), rawPassword));
        return tokenService.issueAccessToken((AuthenticatedUser) result.getPrincipal());
    }

    /**
     * Second half of Google sign-in: the SPA posts the one-time code it got in the redirect.
     * @throws UnauthorizedException INVALID_OAUTH_CODE if unknown, expired, already used, or the user is gone
     */
    public AccessToken exchangeOAuthCode(String code) {
        long userId = oneTimeCodes.redeem(code).orElseThrow(AuthService::invalidCode);
        try {
            return tokenService.issueAccessToken(AuthenticatedUser.from(userService.getById(userId)));
        } catch (NotFoundException ex) {
            throw invalidCode(); // account deleted in the 60 s window
        }
    }

    private static UnauthorizedException invalidCode() {
        return new UnauthorizedException("INVALID_OAUTH_CODE",
                "This sign-in link is invalid or has expired. Please sign in again.");
    }
}
