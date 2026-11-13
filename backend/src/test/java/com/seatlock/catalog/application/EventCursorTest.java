package com.seatlock.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.common.exception.InvalidRequestException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventCursorTest {

    private final EventCursor cursor = new EventCursor(Instant.parse("2026-11-20T14:00:00.123456Z"), 42);

    @Test
    void roundTripKeepsFullInstantPrecision() {
        assertThat(EventCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void encodedCursorIsUrlSafe() {
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "!!!", "MjAyNg", "MjAyNi0xMS0yMFQxNDowMDowMFp8YWJj"})
    void rejectsTamperedCursorWithClientError(String bad) {
        assertThatThrownBy(() -> EventCursor.decode(bad))
                .isInstanceOf(InvalidRequestException.class)
                .extracting("code")
                .isEqualTo("INVALID_CURSOR");
    }
}