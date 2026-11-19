package com.seatlock.payment.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily: delete processed webhook deliveries older than the retention (30 days). Razorpay stops retrying an event
 * long before that (its retry window is about a day), so older rows can never de-duplicate anything again.
 * Idempotent DELETE, safe on two instances.
 */
@Component
public class WebhookEventCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventCleanupJob.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration retention;

    public WebhookEventCleanupJob(JdbcClient jdbc, Clock clock,
                                  @Value("${seatlock.webhooks.retention:P30D}") Duration retention) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "${seatlock.webhooks.cleanup-interval:P1D}")
    public int deleteOld() {
        int deleted = jdbc.sql("DELETE FROM webhook_events WHERE received_at < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(retention)))
                .update();
        if (deleted > 0) {
            log.info("Deleted {} webhook events older than {}", deleted, retention);
        }
        return deleted;
    }
}
