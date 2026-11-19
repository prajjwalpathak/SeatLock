package com.seatlock.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.seatlock.identity.security.TokenAuthenticator;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;

/** Frame rules without a WebSocket: the cases the integration test can't reach cheaply. */
class StompAuthChannelInterceptorTest {

    private final TokenAuthenticator tokens = mock(TokenAuthenticator.class);
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(tokens);
    private final MessageChannel channel = mock(MessageChannel.class);

    private static Message<byte[]> frame(StompCommand command, Map<String, Object> session, String... nativeHeaders) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        for (int i = 0; i < nativeHeaders.length; i += 2) {
            accessor.addNativeHeader(nativeHeaders[i], nativeHeaders[i + 1]);
        }
        accessor.setSessionAttributes(session);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> subscribe(Map<String, Object> session, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionAttributes(session);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void heartBeatsAndOtherNonStompMessagesPassUntouched() {
        SimpMessageHeaderAccessor heartbeat = SimpMessageHeaderAccessor.create(SimpMessageType.HEARTBEAT);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders());

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verifyNoInteractions(tokens);
    }

    @Test
    void aLowerCaseAuthorizationHeaderAndAnyBearerCasingAreAccepted() {
        AbstractAuthenticationToken user = new TestingAuthenticationToken("7", null, "ROLE_USER");
        when(tokens.authenticate("good-token")).thenReturn(user);

        Message<byte[]> connect = frame(StompCommand.CONNECT, new HashMap<>(), "authorization", "bearer good-token");
        interceptor.preSend(connect, channel);

        assertThat(StompHeaderAccessor.wrap(connect).getUser()).isSameAs(user);
    }

    @Test
    void connectWithoutAnyAuthorizationStaysAnonymous() {
        Message<byte[]> connect = frame(StompCommand.STOMP, new HashMap<>());

        interceptor.preSend(connect, channel);

        assertThat(StompHeaderAccessor.wrap(connect).getUser()).isNull();
        verifyNoInteractions(tokens);
    }

    @Test
    void unsubscribingFreesASlotAndTheCounterNeverGoesNegative() {
        Map<String, Object> session = new HashMap<>();
        for (int i = 1; i <= StompAuthChannelInterceptor.MAX_SUBSCRIPTIONS; i++) {
            interceptor.preSend(subscribe(session, SeatTopics.forShow(i)), channel);
        }
        interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, session), channel);
        interceptor.preSend(subscribe(session, SeatTopics.forShow(99)), channel); // fits again

        Map<String, Object> fresh = new HashMap<>();
        interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, fresh), channel);      // nothing to free
        assertThat(fresh.values()).singleElement().hasToString("0");
    }

    @Test
    void withoutSessionAttributesTheCapCannotBeTrackedButRulesStillApply() {
        // outside a real WebSocket session (no attributes) every SUBSCRIBE starts from 0
        for (int i = 0; i < StompAuthChannelInterceptor.MAX_SUBSCRIPTIONS + 2; i++) {
            interceptor.preSend(subscribe(null, SeatTopics.forShow(1)), channel);
        }
        assertThatThrownBy(() -> interceptor.preSend(subscribe(null, "/topic/admin"), channel))
                .isInstanceOfSatisfying(StompRejectedException.class, e -> assertThat(e.code()).isEqualTo("SUBSCRIPTION_DENIED"));
    }

    @Test
    void aBadTokenIsRejectedWithoutTellingWhy() {
        when(tokens.authenticate(anyString()))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("expired at 10:00"));

        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.CONNECT, new HashMap<>(), "Authorization", "Bearer expired"), channel))
                .isInstanceOfSatisfying(StompRejectedException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_TOKEN");
                    assertThat(e.getMessage()).doesNotContain("10:00");
                });
    }
}
