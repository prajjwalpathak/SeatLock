package com.seatlock.identity.domain;

import com.seatlock.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Locale;

@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Column(nullable = false, length = 254)
    private String email;

    /** BCrypt hash; null for Google-only accounts. Never leaves the identity module. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    private AuthProvider authProvider;

    protected User() {
        // for JPA
    }

    public User(String email, String passwordHash, String displayName, Role role, AuthProvider authProvider) {
        this.email = normalizeEmail(email); // DB enforces lower-case too
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.authProvider = authProvider;
    }

    /** The one place that defines how e-mails are compared: trimmed, lower-case. */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public AuthProvider getAuthProvider() { return authProvider; }
}