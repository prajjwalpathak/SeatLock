package com.seatlock.identity.security;

import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** The user as Spring Security sees it during login. Only id, e-mail and role go into the JWT. */
public record AuthenticatedUser(long id, String email, Role role, String passwordHash) implements UserDetails {

    /** Matches no password: used for accounts without one (Google sign-in only). */
    static final String NO_PASSWORD = "!";

    static AuthenticatedUser from(User user) {
        String hash = user.getPasswordHash() != null ? user.getPasswordHash() : NO_PASSWORD;
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole(), hash);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name())); // hasRole('USER') checks ROLE_USER
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + id + ", role=" + role + "]"; // no hash, no e-mail in logs
    }
}
