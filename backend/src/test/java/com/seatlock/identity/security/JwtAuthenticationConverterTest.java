package com.seatlock.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtAuthenticationConverterTest {

    @Test
    void rolesClaimBecomesRoleAuthoritiesAndSubjectIsThePrincipalName() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("42")
                .claim(TokenService.CLAIM_ROLES, List.of("ORGANIZER"))
                .claim("scope", "admin") // a "scope" claim must NOT grant anything
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AbstractAuthenticationToken auth = SecurityConfig.jwtAuthenticationConverter().convert(jwt);

        assertThat(auth.getName()).isEqualTo("42");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ORGANIZER")
                .doesNotContain("SCOPE_admin", "ROLE_USER", "ROLE_ADMIN");
    }
}
