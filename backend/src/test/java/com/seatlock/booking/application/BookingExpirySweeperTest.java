package com.seatlock.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

/** The batch loop: keep going while batches are full, stop on a short batch or the cap, never throw. */
class BookingExpirySweeperTest {

    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");

    private final BookingExpiryService service = mock(BookingExpiryService.class);
    private final BookingExpirySweeper sweeper = new BookingExpirySweeper(service, Clock.fixed(NOW, ZoneOffset.UTC), 100, 3);

    @Test
    void continuesWhileBatchesAreFullAndStopsOnAShortOne() {
        when(service.expireBatch(NOW, 100)).thenReturn(100, 100, 30);

        assertThat(sweeper.sweep()).isEqualTo(230);
        verify(service, times(3)).expireBatch(NOW, 100);
    }

    @Test
    void stopsAtTheCapEvenIfMoreIsLeft() {
        when(service.expireBatch(NOW, 100)).thenReturn(100);

        assertThat(sweeper.sweep()).isEqualTo(300);           // 3 batches max; the next run continues
        verify(service, times(3)).expireBatch(any(), eq(100));
    }

    @Test
    void aFailureIsLoggedAndTheWorkSoFarCounts() {
        when(service.expireBatch(NOW, 100)).thenReturn(100).thenThrow(new CannotAcquireLockException("lock timeout"));

        assertThat(sweeper.sweep()).isEqualTo(100);
    }
}
