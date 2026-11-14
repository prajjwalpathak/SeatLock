package com.seatlock.identity.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Hands our JWT to the SPA without putting it in a URL (ADR-003, option A2): after Google
 * sign-in the browser gets a random one-time code; the SPA swaps it for the JWT with a POST.
 * The code lives 60 s in Redis, works once (GETDEL), and only its SHA-256 hash is stored.
 */
@Service
public class OneTimeCodeService {

    static final String KEY_PREFIX = "seatlock:oauth2:code:";
    static final Duration TTL = Duration.ofSeconds(60);
    private static final int CODE_BYTES = 32; // 256 bits: unguessable

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public OneTimeCodeService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String issue(long userId) {
        byte[] bytes = new byte[CODE_BYTES];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // URL-safe
        redis.opsForValue().set(KEY_PREFIX + sha256(code), Long.toString(userId), TTL);
        return code;
    }

    /** The user id, at most once per code; empty if unknown, expired or already used. */
    public Optional<Long> redeem(String code) {
        if (code == null || code.isBlank() || code.length() > 100) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().getAndDelete(KEY_PREFIX + sha256(code))).map(Long::valueOf);
    }

    static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex); // every JVM has SHA-256
        }
    }
}
