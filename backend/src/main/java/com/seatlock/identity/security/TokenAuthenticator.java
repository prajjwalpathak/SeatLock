package com.seatlock.identity.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

/**
 * Validates one of our access tokens outside the HTTP filter chain (STOMP CONNECT). Same RS256 decoder,
 * validators (issuer, audience, expiry on the app clock) and roles mapping as the resource server, so a
 * token is accepted here exactly when the REST API would accept it.
 */
@Component
public class TokenAuthenticator {

    private final JwtDecoder decoder;
    private final JwtAuthenticationConverter converter = SecurityConfig.jwtAuthenticationConverter();

    public TokenAuthenticator(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /**
     * @return the authenticated caller (name = user id, authorities = {@code ROLE_*})
     * @throws BadCredentialsException for a malformed, forged, expired or foreign token (reason not revealed)
     */
    public AbstractAuthenticationToken authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new BadCredentialsException("Missing token");
        }
        try {
            return converter.convert(decoder.decode(token));
        } catch (JwtException ex) {
            throw new BadCredentialsException("Invalid or expired token", ex);
        }
    }
}
