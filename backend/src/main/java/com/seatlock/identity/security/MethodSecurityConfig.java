package com.seatlock.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables @PreAuthorize on controllers/services. Authorization lives next to the code it protects,
 * so a new endpoint can't silently skip it the way a forgotten URL pattern could.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class MethodSecurityConfig {

    /**
     * ADMIN can do everything an ORGANIZER can, and an ORGANIZER everything a USER can.
     * Static so it exists before method-security infrastructure is built (Spring docs recommend this).
     * Also used by URL rules (hasRole in SecurityConfig).
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("ORGANIZER")
                .role("ORGANIZER").implies("USER")
                .build();
    }
}
