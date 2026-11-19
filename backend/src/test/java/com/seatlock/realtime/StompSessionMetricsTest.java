package com.seatlock.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class StompSessionMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final StompSessionMetrics metrics = new StompSessionMetrics(meters);

    @Test
    void countsOpenSessionsAndIgnoresDuplicateDisconnects() {
        metrics.onConnected(connected("s1"));
        metrics.onConnected(connected("s2"));
        assertThat(gauge()).isEqualTo(2.0);

        metrics.onDisconnect(disconnected("s1"));
        metrics.onDisconnect(disconnected("s1"));   // Spring may publish a disconnect twice for one session
        assertThat(gauge()).isEqualTo(1.0);

        metrics.onDisconnect(disconnected("unknown"));
        assertThat(metrics.openSessions()).isEqualTo(1);
    }

    private double gauge() {
        return meters.get(StompSessionMetrics.METER).gauge().value();
    }

    private static Message<byte[]> message(String sessionId) {
        return MessageBuilder.withPayload(new byte[0])
                .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId)
                .build();
    }

    private static SessionConnectedEvent connected(String sessionId) {
        return new SessionConnectedEvent(StompSessionMetricsTest.class, message(sessionId));
    }

    private static SessionDisconnectEvent disconnected(String sessionId) {
        return new SessionDisconnectEvent(StompSessionMetricsTest.class, message(sessionId), sessionId,
                CloseStatus.NORMAL);
    }
}
