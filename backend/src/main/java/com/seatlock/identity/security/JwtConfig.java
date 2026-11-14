package com.seatlock.identity.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * RS256: we sign with the private key, and anything holding the public key can verify
 * (no shared secret to leak). Both encoder and decoder are Spring Security's Nimbus wrappers.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    /** Tolerates small clock differences between servers. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Bean
    RSAKey jwtSigningKey(JwtProperties properties) {
        boolean configured = hasText(properties.privateKey()) || hasText(properties.publicKey());
        KeyPair pair;
        if (configured) {
            pair = RsaKeys.parse(properties.privateKey(), properties.publicKey());
        } else if (properties.allowEphemeralKeys()) {
            log.warn("No JWT keys configured: using a throwaway RSA key pair (tokens die on restart)");
            pair = RsaKeys.generate();
        } else {
            throw new IllegalStateException(
                    "JWT keys missing: set JWT_PRIVATE_KEY and JWT_PUBLIC_KEY (see .env.example)");
        }
        try {
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyIDFromThumbprint() // "kid" header = hash of the public key; enables rotation later
                    .build();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not build the JWT signing key", ex);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey jwtSigningKey, JwtProperties properties, Clock clock) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256) // only RS256; "none"/HS256 tricks are rejected
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(JwtProperties properties, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock); // same app clock as everything else: expiry is testable
        JwtClaimValidator<List<String>> audience = new JwtClaimValidator<>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(properties.audience()));
        return new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(properties.issuer()), audience);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
