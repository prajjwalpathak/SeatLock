package com.seatlock.identity.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.application.AuthService;
import com.seatlock.identity.application.UserService;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.JwtProperties;
import com.seatlock.identity.security.TokenService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import testsupport.IntegrationTest;

/** Resource-server side: which tokens open GET /api/v1/me and which are rejected. */
@IntegrationTest
class MeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired AuthService authService;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtProperties jwtProperties;

    User user;

    @BeforeEach
    void newUser() {
        user = userService.register("me-" + UUID.randomUUID() + "@example.com", "correct-horse-battery", "Me Test");
    }

    @Test
    void validTokenReturnsCurrentUser() throws Exception {
        String token = authService.login(user.getEmail(), "correct-horse-battery").value();

        me(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void noTokenIs401AuthenticationRequired() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = authService.login(user.getEmail(), "correct-horse-battery").value();
        String[] parts = token.split("\\.");
        // Claim to be an ADMIN, keep the original signature
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"USER\"", "\"ADMIN\"");
        String tampered = parts[0] + "." + b64url(payload) + "." + parts[2];

        expectInvalidToken(me(tampered));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        // Issued one full TTL (+ more than the 30 s skew) ago, so it expired a minute ago, whatever the TTL is
        Instant issuedAt = Instant.now().minus(jwtProperties.accessTokenTtl()).minus(Duration.ofMinutes(1));
        String expired = new TokenService(jwtEncoder, jwtProperties, Clock.fixed(issuedAt, ZoneOffset.UTC))
                .issueAccessToken(principal()).value();

        expectInvalidToken(me(expired));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair attackerKeys = generator.generateKeyPair();
        RSAKey attackerJwk = new RSAKey.Builder((RSAPublicKey) attackerKeys.getPublic())
                .privateKey((RSAPrivateKey) attackerKeys.getPrivate()).build();
        JwtEncoder attackerEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(attackerJwk)));
        String forged = new TokenService(attackerEncoder, jwtProperties, Clock.systemUTC())
                .issueAccessToken(principal()).value();

        expectInvalidToken(me(forged));
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = b64url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String claims = b64url("""
                {"iss":"seatlock","aud":"seatlock-api","sub":"%d","roles":["ADMIN"],"iat":%d,"exp":%d}"""
                .formatted(user.getId(), now, now + 600));

        expectInvalidToken(me(header + "." + claims + "."));
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        expectInvalidToken(me("not.a.jwt"));
    }

    private AuthenticatedUser principal() {
        return new AuthenticatedUser(user.getId(), user.getEmail(), Role.USER, "unused");
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static void expectInvalidToken(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
