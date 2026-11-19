package com.seatlock.realtime;

import com.seatlock.catalog.events.SeatsChangedEvent;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Observer: turns committed seat changes into relayed seat events (Redis Pub/Sub → every instance's broker).
 * <p>AFTER_COMMIT, so viewers never see a change that rolls back; {@code fallbackExecution} covers
 * publishers without a transaction (Redis holds), which are sent immediately.</p>
 * <p>Best effort by design: the change is already committed, so a failure here (Redis down) is logged and
 * swallowed, never turned into an error for the user who made the change. Clients recover by resyncing
 * when they notice a version gap.</p>
 */
@Component
class SeatEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SeatEventBroadcaster.class);

    private final SeatEventRelay relay;
    private final Clock clock;

    SeatEventBroadcaster(SeatEventRelay relay, Clock clock) {
        this.relay = relay;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSeatsChanged(SeatsChangedEvent event) {
        try {
            long version = relay.publish(event, clock.instant());
            log.debug("Seat event show={} v={} {} {}", event.showId(), version, event.status(), event.seatIds());
        } catch (RuntimeException e) {
            log.warn("Seat event for show {} not published ({}); viewers will resync", event.showId(), e.toString());
        }
    }
}
