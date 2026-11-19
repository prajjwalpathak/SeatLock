package com.seatlock.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SeatTopicsTest {

    @Test
    void roundTrips() {
        assertThat(SeatTopics.forShow(42)).isEqualTo("/topic/shows/42/seats");
        assertThat(SeatTopics.showIdOf("/topic/shows/42/seats")).hasValue(42);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "/topic/shows/0/seats", "/topic/shows/-1/seats", "/topic/shows/abc/seats",
            "/topic/shows/*/seats", "/topic/shows/**", "/topic/shows/1/seats/", "/topic/shows/01/seats",
            "/topic/shows/1234567890123456789/seats", "/user/queue/errors", "/topic/admin"})
    void rejectsEverythingElse(String destination) {
        assertThat(SeatTopics.showIdOf(destination)).isEmpty();
    }
}
