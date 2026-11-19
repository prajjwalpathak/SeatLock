package com.seatlock.realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Gauge {@code seatlock.websocket.sessions}: STOMP sessions open on this instance (connected, not yet closed).
 * Session ids are kept in a set rather than a +1/-1 counter because Spring may publish more than one
 * {@link SessionDisconnectEvent} for a session; removing an id twice is harmless, decrementing twice is not.
 */
@Component
public class StompSessionMetrics {

    static final String METER = "seatlock.websocket.sessions";

    private final Set<String> open = ConcurrentHashMap.newKeySet();

    public StompSessionMetrics(MeterRegistry meters) {
        Gauge.builder(METER, open, Set::size)
                .description("Open STOMP sessions on this instance")
                .register(meters);
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        open.add(sessionId(event.getMessage().getHeaders()));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        open.remove(event.getSessionId());
    }

    int openSessions() {
        return open.size();
    }

    private static String sessionId(org.springframework.messaging.MessageHeaders headers) {
        return String.valueOf(SimpMessageHeaderAccessor.getSessionId(headers));
    }
}
