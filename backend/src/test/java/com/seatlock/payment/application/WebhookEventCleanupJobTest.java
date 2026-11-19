package com.seatlock.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import testsupport.IntegrationTest;

@IntegrationTest
class WebhookEventCleanupJobTest {

    @Autowired JdbcClient jdbcClient;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM webhook_events WHERE event_id LIKE 'evt_cleanup_%'");
    }

    @Test
    void deletesOnlyRowsOlderThanTheRetention() {
        insert("evt_cleanup_old", "now() - interval '31 days'");
        insert("evt_cleanup_recent", "now() - interval '2 days'");

        new WebhookEventCleanupJob(jdbcClient, clock, Duration.ofDays(30)).deleteOld();

        assertThat(jdbc.queryForList("SELECT event_id FROM webhook_events WHERE event_id LIKE 'evt_cleanup_%'",
                String.class)).containsExactly("evt_cleanup_recent");
    }

    private void insert(String id, String receivedAt) {
        jdbc.update("""
                INSERT INTO webhook_events (provider, event_id, event_type, payload_sha256, outcome, received_at)
                VALUES ('RAZORPAY', ?, 'payment.captured', repeat('0', 64), 'CONFIRMED', %s)""".formatted(receivedAt), id);
    }
}
