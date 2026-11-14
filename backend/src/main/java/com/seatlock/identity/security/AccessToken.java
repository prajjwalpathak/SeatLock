package com.seatlock.identity.security;

import java.time.Duration;
import java.time.Instant;

/** A signed access token and when it stops being valid. */
public record AccessToken(String value, Instant expiresAt, Duration ttl) {

    @Override
    public String toString() {
        return "AccessToken[expiresAt=" + expiresAt + "]"; // never print the token itself
    }
}
