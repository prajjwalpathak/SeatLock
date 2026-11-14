package com.seatlock.identity.application;

import com.seatlock.identity.domain.User;
import com.seatlock.identity.security.AccessToken;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.nio.charset.StandardCharsets;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** E-mail + password → access token. */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;

    public AuthService(AuthenticationManager authenticationManager, TokenService tokenService) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
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
}
