package com.seatlock.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatlock.booking.application.BookingService;
import com.seatlock.common.exception.ConflictException;
import com.seatlock.identity.application.UserService;
import com.seatlock.payment.application.PaidCancellationService.QuoteView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** W8·D1: the decision part of the demo reset (which bookings are cancelled), without a database. */
class DemoBookingResetJobTest {

    static final Instant NOW = Instant.parse("2026-12-01T22:00:00Z");

    final UserService users = mock(UserService.class);
    final BookingService bookings = mock(BookingService.class);
    final PaidCancellationService cancellations = mock(PaidCancellationService.class);

    DemoBookingResetJob job(boolean enabled) {
        return new DemoBookingResetJob(users, bookings, cancellations, Clock.fixed(NOW, ZoneOffset.UTC), enabled,
                "demo.user@seatlock.test", Duration.ofHours(12), 50);
    }

    @Test
    void onlyFullRefundsAreCancelledAndOneFailureDoesNotStopTheRest() {
        when(bookings.confirmedBookingIds(7L, NOW.minus(Duration.ofHours(12)), 50)).thenReturn(List.of("a", "b", "c", "d"));
        when(cancellations.quote(7L, "a")).thenReturn(quote(true, 100));
        when(cancellations.quote(7L, "b")).thenReturn(quote(true, 50));    // partial: left alone
        when(cancellations.quote(7L, "c")).thenThrow(new ConflictException("X", "boom"));
        when(cancellations.quote(7L, "d")).thenReturn(quote(true, 100));

        assertThat(job(true).resetBookingsOf(7L)).isEqualTo(2);

        verify(cancellations).cancel(7L, "a");
        verify(cancellations).cancel(7L, "d");
        verify(cancellations, never()).cancel(7L, "b");
        verify(cancellations, never()).cancel(7L, "c");
    }

    @Test
    void nightlyUsesTheDemoAccountOnlyAndIsSkippedWhenDisabled() {
        when(users.findIdByEmail("demo.user@seatlock.test")).thenReturn(Optional.of(7L));
        when(bookings.confirmedBookingIds(eq(7L), any(), anyInt())).thenReturn(List.of());

        job(true).nightly();
        verify(bookings).confirmedBookingIds(eq(7L), any(), anyInt());

        job(false).nightly();
        verify(users).findIdByEmail(anyString()); // still only the one call from the enabled run
    }

    @Test
    void aMissingDemoAccountIsNotAnError() {
        when(users.findIdByEmail(anyString())).thenReturn(Optional.empty());

        job(true).nightly();

        verify(bookings, never()).confirmedBookingIds(anyLong(), any(), anyInt());
    }

    private static QuoteView quote(boolean cancellable, int percent) {
        return new QuoteView(cancellable, percent, percent * 100L, 10_000L, null, "test");
    }
}
