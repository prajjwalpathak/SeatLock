package com.seatlock.identity.api;

import com.seatlock.identity.security.AccessToken;

/** OAuth2-style token response. {@code expiresIn} is in seconds. No refresh token (sign in again on expiry). */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static TokenResponse bearer(AccessToken token) {
        return new TokenResponse(token.value(), "Bearer", token.ttl().toSeconds());
    }

    @Override
    public String toString() {
        return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
