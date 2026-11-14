package com.seatlock.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import testsupport.IntegrationTest;

@IntegrationTest
class OneTimeCodeServiceTest {

    @Autowired OneTimeCodeService codes;
    @Autowired StringRedisTemplate redis;

    @Test
    void codeRedeemsOnceThenNeverAgain() {
        String code = codes.issue(42);

        assertThat(code).matches("[A-Za-z0-9_-]{43}"); // 256 bits, URL-safe, no padding
        assertThat(codes.redeem(code)).contains(42L);
        assertThat(codes.redeem(code)).isEmpty(); // replay
    }

    @Test
    void onlyAHashIsStoredAndItExpiresWithinAMinute() {
        String code = codes.issue(7);

        String key = OneTimeCodeService.KEY_PREFIX + OneTimeCodeService.sha256(code);
        assertThat(redis.hasKey(OneTimeCodeService.KEY_PREFIX + code)).isFalse(); // raw code not a key
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
    }

    @Test
    void unknownBlankOrHugeCodesRedeemNothing() {
        assertThat(codes.redeem("made-up-code")).isEmpty();
        assertThat(codes.redeem("")).isEmpty();
        assertThat(codes.redeem(null)).isEmpty();
        assertThat(codes.redeem("x".repeat(500))).isEmpty();
    }
}
