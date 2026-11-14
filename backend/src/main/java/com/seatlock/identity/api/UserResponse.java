package com.seatlock.identity.api;

import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;

/** Public view of an account. Never includes the password hash. */
public record UserResponse(long id, String email, String displayName, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }
}
