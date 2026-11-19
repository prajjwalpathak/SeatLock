package com.seatlock.realtime;

import com.seatlock.identity.security.TokenAuthenticator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Rules for every frame a client sends (inbound channel, before the broker sees it):
 * <ul>
 *   <li><b>CONNECT</b>: {@code Authorization: Bearer <jwt>} is optional (the seat map is public), but a
 *       header that is present must hold a valid token, else ERROR {@code INVALID_TOKEN}. The token travels
 *       in the CONNECT frame because browsers can't set headers on the WebSocket handshake.</li>
 *   <li><b>SUBSCRIBE</b>: only {@code /topic/shows/{id}/seats} (no wildcards, no other prefixes), at most
 *       {@value #MAX_SUBSCRIPTIONS} per connection.</li>
 *   <li><b>SEND</b>: always refused; otherwise a client could publish forged seat events into {@code /topic}.</li>
 * </ul>
 * The token is checked once, at CONNECT; current topics are public, so a token expiring on an open
 * connection grants nothing extra. User-specific destinations would need a re-check on SUBSCRIBE.
 */
@Component
class StompAuthChannelInterceptor implements ChannelInterceptor {

    static final int MAX_SUBSCRIPTIONS = 10;
    private static final String SUBSCRIPTIONS = StompAuthChannelInterceptor.class.getName() + ".subscriptions";
    private static final String BEARER = "bearer ";

    private final TokenAuthenticator tokens;

    StompAuthChannelInterceptor(TokenAuthenticator tokens) {
        this.tokens = tokens;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heart-beats
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> authenticate(accessor);
            case SUBSCRIBE -> checkSubscription(accessor);
            case UNSUBSCRIBE -> subscriptions(accessor).updateAndGet(n -> Math.max(0, n - 1));
            case SEND -> throw new StompRejectedException("SEND_NOT_ALLOWED", "Clients can't send messages");
            default -> { /* DISCONNECT, ACK, NACK, … */ }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null) {
            header = accessor.getFirstNativeHeader("authorization");
        }
        if (header == null) {
            return; // anonymous viewer
        }
        if (header.length() <= BEARER.length() || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new StompRejectedException("INVALID_TOKEN", "Authorization must be 'Bearer <token>'");
        }
        try {
            accessor.setUser(tokens.authenticate(header.substring(BEARER.length()).trim()));
        } catch (AuthenticationException ex) {
            throw new StompRejectedException("INVALID_TOKEN", "Invalid or expired token");
        }
    }

    private void checkSubscription(StompHeaderAccessor accessor) {
        if (SeatTopics.showIdOf(accessor.getDestination()).isEmpty()) {
            throw new StompRejectedException("SUBSCRIPTION_DENIED", "Only /topic/shows/{id}/seats can be subscribed to");
        }
        if (subscriptions(accessor).incrementAndGet() > MAX_SUBSCRIPTIONS) {
            throw new StompRejectedException("TOO_MANY_SUBSCRIPTIONS",
                    "At most " + MAX_SUBSCRIPTIONS + " subscriptions per connection");
        }
    }

    /** Per-connection counter, kept in the WebSocket session's attributes (null only outside a real session). */
    private static AtomicInteger subscriptions(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            return new AtomicInteger();
        }
        return (AtomicInteger) attributes.computeIfAbsent(SUBSCRIPTIONS, k -> new AtomicInteger());
    }
}
