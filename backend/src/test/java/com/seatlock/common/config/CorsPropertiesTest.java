package com.seatlock.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** W8·D1: only exact origins can be configured (a typo can't silently open CORS). */
class CorsPropertiesTest {

    @Test
    void exactOriginsAreAcceptedAndTrimmed() {
        CorsProperties p = new CorsProperties(List.of(" https://seatlock-nu.vercel.app ", "http://localhost:5173"));
        assertThat(p.allowedOrigins()).containsExactly("https://seatlock-nu.vercel.app", "http://localhost:5173");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.vercel.app", "https://seatlock-nu.vercel.app/", "https://a.b/path",
            "seatlock-nu.vercel.app", "ftp://example.com", "null"})
    void anythingElseFailsStartup(String origin) {
        assertThatThrownBy(() -> new CorsProperties(List.of(origin)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an exact origin");
    }
}
