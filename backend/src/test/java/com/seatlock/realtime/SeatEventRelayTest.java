package com.seatlock.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.catalog.domain.ShowSeatStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SeatEventRelayTest {

    @Test
    void decodesTheVersionPrefixAndTheEvent() {
        byte[] wire = ("17|{\"showId\":5,\"status\":\"HELD\",\"seatIds\":[3,4],"
                + "\"holdExpiresAt\":\"2026-12-01T12:08:00Z\",\"occurredAt\":\"2026-12-01T12:00:00Z\"}")
                .getBytes(StandardCharsets.UTF_8);

        SeatEventMessage m = SeatEventRelay.decode(wire);

        assertThat(m.version()).isEqualTo(17);
        assertThat(m.showId()).isEqualTo(5);
        assertThat(m.status()).isEqualTo(ShowSeatStatus.HELD);
        assertThat(m.seatIds()).containsExactly(3L, 4L);
        assertThat(m.holdExpiresAt()).isEqualTo(Instant.parse("2026-12-01T12:08:00Z"));
    }

    @Test
    void rejectsMessagesWithoutAVersion() {
        assertThatThrownBy(() -> SeatEventRelay.decode("{\"showId\":5}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatEventRelay.decode("|{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
