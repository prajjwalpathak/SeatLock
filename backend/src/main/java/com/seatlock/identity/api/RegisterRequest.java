package com.seatlock.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sign-up body. Password rules follow NIST 800-63B: length matters, composition rules don't.
 * The 72-byte BCrypt limit is checked in UserService (it counts bytes, not characters).
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String displayName) {

    /** Records print every field by default; keep the password out of logs and debugger output. */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***, displayName=" + displayName + "]";
    }
}
