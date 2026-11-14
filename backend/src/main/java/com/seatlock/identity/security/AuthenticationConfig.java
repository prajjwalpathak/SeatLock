package com.seatlock.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Username/password authentication for POST /auth/login. Kept apart from SecurityConfig so web
 * slice tests can import the URL rules without needing the database.
 */
@Configuration(proxyBeanMethods = false)
public class AuthenticationConfig {

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Defaults we rely on: "user not found" becomes BadCredentialsException, and a dummy BCrypt
        // check runs for unknown e-mails, so response time doesn't reveal whether an account exists.
        return new ProviderManager(provider);
    }
}
