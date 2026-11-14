package com.seatlock.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only presence and an upper bound are checked: login must not reveal the sign-up rules. */
public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 256) String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
