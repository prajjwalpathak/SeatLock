package com.seatlock.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class IdempotencyCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");

    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final IdempotencyCleanupJob job = new IdempotencyCleanupJob(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void deletesKeysExpiredAsOfNow() {
        when(store.deleteExpired(NOW)).thenReturn(3);

        assertThat(job.deleteExpiredKeys()).isEqualTo(3);
        verify(store).deleteExpired(NOW);
    }

    @Test
    void aFailedRunDoesNotThrowSoTheScheduleKeepsGoing() {
        when(store.deleteExpired(NOW)).thenThrow(new DataAccessResourceFailureException("db restarting"));

        assertThat(job.deleteExpiredKeys()).isZero();
    }
}
