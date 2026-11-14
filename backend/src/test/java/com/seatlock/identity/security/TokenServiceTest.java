package com.seatlock.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import com.seatlock.identity.domain.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TokenServiceTest {

    static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    final JwtConfig config = new JwtConfig();
    final RSAKey key = config.jwtSigningKey(JwtConfigTest.PROPS);
    final TokenService tokens = new TokenService(config.jwtEncoder(key), JwtConfigTest.PROPS, CLOCK);

    @Test
    void issuesVerifiableTokenWithIdRolesAndFifteenMinuteExpiry() throws Exception {
        var user = new AuthenticatedUser(42, "asha@example.com", Role.ORGANIZER, "{bcrypt}x");

        AccessToken token = tokens.issueAccessToken(user);
        Jwt jwt = config.jwtDecoder(key, JwtConfigTest.PROPS, CLOCK).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString(TokenService.CLAIM_EMAIL)).isEqualTo("asha@example.com");
        assertThat(jwt.getClaimAsStringList(TokenService.CLAIM_ROLES)).containsExactly("ORGANIZER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("seatlock"); // getIssuer() expects a URL
        assertThat(jwt.getAudience()).containsExactly("seatlock-api");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15))).isEqualTo(token.expiresAt());
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaims()).doesNotContainKey("password");
    }

    @Test
    void everyTokenHasItsOwnId() {
        var user = new AuthenticatedUser(1, "a@example.com", Role.USER, "{bcrypt}x");
        assertThat(tokens.issueAccessToken(user).value()).isNotEqualTo(tokens.issueAccessToken(user).value());
    }

    @Test
    void toStringsNeverExposeSecrets() {
        var user = new AuthenticatedUser(1, "a@example.com", Role.USER, "{bcrypt}secret-hash");
        AccessToken token = tokens.issueAccessToken(user);

        assertThat(user.toString()).doesNotContain("secret-hash").doesNotContain("a@example.com");
        assertThat(token.toString()).doesNotContain(token.value());
    }
}
