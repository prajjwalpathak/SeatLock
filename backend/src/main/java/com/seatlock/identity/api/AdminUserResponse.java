package com.seatlock.identity.api;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import java.time.Instant;

/** Admin view of an account (still no password hash). */
public record AdminUserResponse(long id, String email, String displayName, Role role,
                                AuthProvider authProvider, Instant createdAt) {

    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.getAuthProvider(), user.getCreatedAt());
    }
}
