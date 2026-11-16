package com.seatlock.common.idempotency;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes idempotency keys past their {@code expires_at} (24 h), so the table stays small.
 * <p>Safe with two app instances (W5): both may run it, and a DELETE of already-deleted rows is a no-op,
 * so no distributed lock is needed. The {@code expires_at} index keeps each run cheap.</p>
 */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyStore store;
    private final Clock clock;

    public IdempotencyCleanupJob(IdempotencyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Hourly by default, first run 1 minute after startup. ISO-8601 durations (PT1H). */
    @Scheduled(initialDelayString = "${seatlock.idempotency.cleanup-initial-delay:PT1M}",
               fixedDelayString = "${seatlock.idempotency.cleanup-interval:PT1H}")
    public int deleteExpiredKeys() {
        try {
            int deleted = store.deleteExpired(clock.instant());
            if (deleted > 0) {
                log.info("Deleted {} expired idempotency keys", deleted);
            }
            return deleted;
        } catch (RuntimeException e) {
            // Never let one failed run (e.g. DB restarting) stop the schedule; the next run catches up.
            log.warn("Idempotency key cleanup failed; will retry next run ({})", e.toString());
            return 0;
        }
    }
}
