package com.seatlock.realtime;

import com.seatlock.catalog.events.SeatsChangedEvent;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Observer: turns committed seat changes into STOMP messages for everyone watching the show.
 * <p>AFTER_COMMIT, so viewers never see a change that rolls back; {@code fallbackExecution} covers
 * publishers without a transaction (Redis holds), which are broadcast immediately.</p>
 * <p>Best effort by design: the change is already committed, so a failure here (Redis down, broker
 * stopped) is logged and swallowed, never turned into an error for the user who made the change.
 * Clients recover by re-fetching the seat map when they notice a version gap.</p>
 */
@Component
class SeatEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SeatEventBroadcaster.class);

    private final SimpMessagingTemplate broker;
    private final SeatEventVersions versions;
    private final Clock clock;

    SeatEventBroadcaster(SimpMessagingTemplate broker, SeatEventVersions versions, Clock clock) {
        this.broker = broker;
        this.versions = versions;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSeatsChanged(SeatsChangedEvent event) {
        try {
            long version = versions.next(event.showId());
            SeatEventMessage message = new SeatEventMessage(event.showId(), version, event.status(),
                    event.seatIds(), event.holdExpiresAt(), clock.instant());
            broker.convertAndSend(SeatTopics.forShow(event.showId()), message);
            log.debug("Seat event show={} v={} {} {}", event.showId(), version, event.status(), event.seatIds());
        } catch (RuntimeException e) {
            log.warn("Seat event for show {} not broadcast ({}); viewers will resync", event.showId(), e.toString());
        }
    }
}
