package com.seatlock.identity.api;

import com.seatlock.identity.application.AuthService;
import com.seatlock.identity.application.UserService;
import com.seatlock.identity.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Sign up and sign in (Google: open /oauth2/authorization/google in the browser)")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final AuthService authService;

    public AuthController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @Operation(summary = "Create a USER account (409 EMAIL_TAKEN if it exists)")
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request.email(), request.password(), request.displayName());
        return ResponseEntity.created(URI.create("/api/v1/me")).body(UserResponse.from(user));
    }

    @Operation(summary = "Sign in with e-mail + password; returns a Bearer access token (401 INVALID_CREDENTIALS)")
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse body = TokenResponse.bearer(authService.login(request.email(), request.password()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()) // RFC 6749 §5.1: token responses must not be cached
                .body(body);
    }

    @Operation(summary = "Swap the one-time code from Google sign-in for a Bearer token (401 INVALID_OAUTH_CODE)")
    @PostMapping("/oauth/exchange")
    public ResponseEntity<TokenResponse> exchangeOAuthCode(@Valid @RequestBody OAuthCodeExchangeRequest request) {
        TokenResponse body = TokenResponse.bearer(authService.exchangeOAuthCode(request.code()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
