package com.seatlock.identity.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT settings ({@code seatlock.jwt.*}).
 *
 * @param privateKey         RSA private key, PKCS#8 (base64, PEM headers optional)
 * @param publicKey          matching RSA public key, X.509 SubjectPublicKeyInfo
 * @param allowEphemeralKeys if true and no keys are set, generate a throwaway pair at startup
 *                           (tests only: tokens die on restart and differ between instances)
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration accessTokenTtl,
        String privateKey,
        String publicKey,
        boolean allowEphemeralKeys) {
}
