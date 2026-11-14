package com.seatlock.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Encoder + decoder wiring, without Spring: sign, verify, and every rejection rule. */
class JwtConfigTest {

    static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    static final JwtProperties PROPS =
            new JwtProperties("seatlock", "seatlock-api", Duration.ofMinutes(15), null, null, true);

    final JwtConfig config = new JwtConfig();
    final RSAKey key = config.jwtSigningKey(PROPS);
    final JwtEncoder encoder = config.jwtEncoder(key);

    @Test
    void signedTokenRoundTripsWithKeyId() throws Exception {
        Jwt jwt = decoderAt(NOW.plusSeconds(60)).decode(token("seatlock", "seatlock-api", NOW));

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getHeaders()).containsEntry("kid", key.getKeyID()).containsEntry("alg", "RS256");
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = token("seatlock", "seatlock-api", NOW);
        // valid until NOW + 15 min; skew allows 30 s more
        assertThatThrownBy(() -> decoderAt(NOW.plus(Duration.ofMinutes(16))).decode(token))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void wrongIssuerOrAudienceIsRejected() throws Exception {
        JwtDecoder decoder = decoderAt(NOW);
        assertThatThrownBy(() -> decoder.decode(token("evil", "seatlock-api", NOW)))
                .isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode(token("seatlock", "other-api", NOW)))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        JwtEncoder attacker = config.jwtEncoder(config.jwtSigningKey(PROPS)); // fresh, different key pair
        String forged = attacker.encode(params("seatlock", "seatlock-api", NOW)).getTokenValue();

        assertThatThrownBy(() -> decoderAt(NOW).decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void missingKeysFailStartupUnlessEphemeralAllowed() {
        var strict = new JwtProperties("seatlock", "seatlock-api", Duration.ofMinutes(15), "", "", false);
        assertThatThrownBy(() -> config.jwtSigningKey(strict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY");
    }

    private JwtDecoder decoderAt(Instant instant) throws Exception {
        return config.jwtDecoder(key, PROPS, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private String token(String issuer, String audience, Instant issuedAt) {
        return encoder.encode(params(issuer, audience, issuedAt)).getTokenValue();
    }

    private static JwtEncoderParameters params(String issuer, String audience, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(java.util.List.of(audience))
                .subject("42")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                .build();
        return JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims);
    }
}
