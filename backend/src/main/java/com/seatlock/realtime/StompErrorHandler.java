package com.seatlock.realtime;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * ERROR frames carry a stable code in the {@code message} header and a short description as the body.
 * Anything unexpected becomes {@code INTERNAL_ERROR} with no details (like our 500s: nothing internal leaks).
 */
@Component
class StompErrorHandler extends StompSubProtocolErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(StompErrorHandler.class);

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        StompRejectedException rejected = findRejection(ex);
        String code;
        String description;
        if (rejected != null) {
            code = rejected.code();
            description = rejected.getMessage();
            log.debug("STOMP frame rejected: {}", code);
        } else {
            code = "INTERNAL_ERROR";
            description = "Unexpected error";
            log.warn("STOMP frame failed", ex);
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(code);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(description.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private static StompRejectedException findRejection(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof StompRejectedException r) {
                return r;
            }
        }
        return null;
    }
}
