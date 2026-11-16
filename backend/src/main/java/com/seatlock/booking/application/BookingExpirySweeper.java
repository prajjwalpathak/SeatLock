package com.seatlock.booking.application;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 30 s: expire unpaid bookings whose {@code expiresAt} has passed, in batches (one transaction each).
 * Safe on two instances: SKIP LOCKED makes them split the rows instead of colliding. Capped per run so one
 * huge backlog can't hold a scheduler thread for long; the next run continues.
 */
@Component
public class BookingExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(BookingExpirySweeper.class);

    private final BookingExpiryService expiry;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public BookingExpirySweeper(BookingExpiryService expiry, Clock clock,
                                @Value("${seatlock.bookings.sweep-batch-size:100}") int batchSize,
                                @Value("${seatlock.bookings.sweep-max-batches:20}") int maxBatchesPerRun) {
        this.expiry = expiry;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    @Scheduled(initialDelayString = "${seatlock.bookings.sweep-initial-delay:PT30S}",
               fixedDelayString = "${seatlock.bookings.sweep-interval:PT30S}")
    public int sweep() {
        int total = 0;
        try {
            for (int batch = 0; batch < maxBatchesPerRun; batch++) {
                int expired = expiry.expireBatch(clock.instant(), batchSize);
                total += expired;
                if (expired < batchSize) {
                    break; // nothing (visible to us) left
                }
            }
        } catch (RuntimeException e) {
            // Keep the schedule alive (DB restarting, a version conflict…); the next run retries.
            log.warn("Booking expiry sweep failed after {} bookings; will retry next run ({})", total, e.toString());
        }
        if (total > 0) {
            log.info("Expired {} unpaid bookings", total);
        }
        return total;
    }
}
