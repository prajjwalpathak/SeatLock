package com.seatlock.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code seatlock.admin.*}, from SEATLOCK_ADMIN_EMAIL / SEATLOCK_ADMIN_PASSWORD.
 * Both empty = no admin bootstrap.
 */
@ConfigurationProperties(prefix = "seatlock.admin")
public record AdminBootstrapProperties(String email, String password) {

    @Override
    public String toString() {
        return "AdminBootstrapProperties[email=" + email + ", password=***]";
    }
}
