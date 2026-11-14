package com.seatlock.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The one-time code from {@code /auth/callback?code=…} after Google sign-in. */
public record OAuthCodeExchangeRequest(@NotBlank @Size(max = 100) String code) {

    @Override
    public String toString() {
        return "OAuthCodeExchangeRequest[code=***]"; // a live code is as good as a token for 60 s
    }
}
