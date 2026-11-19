package com.seatlock.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Receiving end of the relay: every seat event on the channel goes to this instance's STOMP broker. */
@Component
class SeatEventRelayListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SeatEventRelayListener.class);

    private final SimpMessagingTemplate broker;

    SeatEventRelayListener(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            SeatEventMessage event = SeatEventRelay.decode(message.getBody());
            broker.convertAndSend(SeatTopics.forShow(event.showId()), event);
        } catch (RuntimeException e) {
            log.warn("Relayed seat event dropped ({}); viewers will resync", e.toString());
        }
    }
}
