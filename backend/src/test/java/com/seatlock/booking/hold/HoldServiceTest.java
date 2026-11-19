package com.seatlock.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.seatlock.catalog.application.HoldableShow;
import com.seatlock.catalog.application.ShowCatalogService;
import com.seatlock.catalog.domain.ShowSeatStatus;
import com.seatlock.common.exception.ConflictException;
import com.seatlock.common.exception.InvalidRequestException;
import com.seatlock.common.exception.NotFoundException;
import com.seatlock.common.exception.SeatLockException;
import com.seatlock.common.exception.ServiceUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import com.seatlock.catalog.events.SeatsChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;

class HoldServiceTest {

    private static final long USER = 7L;
    private static final long SHOW = 42L;
    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(8);
    private static final String HOLD_ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b";

    private final ShowCatalogService catalog = mock(ShowCatalogService.class);
    private final RedisHoldStore store = mock(RedisHoldStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final HoldService service = new HoldService(catalog, store, new HoldProperties(TTL, 6),
            Clock.fixed(NOW, ZoneOffset.UTC), events, meters);

    {
        when(store.name()).thenReturn("redis-hold");   // tag on seatlock.holds
    }

    // ------------------------------------------------------------------ create

    @Test
    void holdsSeatsWithServerSidePricesAndAnExpiryFromTheClock() {
        showStartsIn(Duration.ofDays(2), seat(1, 150_000, ShowSeatStatus.AVAILABLE), seat(2, 75_000, ShowSeatStatus.AVAILABLE));
        when(store.tryHold(anyString(), eq(USER), eq(SHOW), eq(List.of(1L, 2L)), eq(TTL), eq(NOW.plus(TTL))))
                .thenAnswer(inv -> new HoldAttempt.Held(inv.getArgument(0)));

        Hold hold = service.createHold(USER, SHOW, List.of(1L, 2L));

        assertThat(hold.holdId()).hasSize(36);                 // UUID
        assertThat(hold.seats()).extracting(Hold.HeldSeat::seatId).containsExactly(1L, 2L);
        assertThat(hold.totalPaise()).isEqualTo(225_000);
        assertThat(hold.expiresAt()).isEqualTo(NOW.plus(TTL));
        verify(events).publishEvent((Object) new SeatsChangedEvent(SHOW, List.of(1L, 2L), ShowSeatStatus.HELD, NOW.plus(TTL)));
        assertThat(holds(HoldService.OUTCOME_HELD)).isEqualTo(1.0);
    }

    @Test
    void expiryIsTruncatedToMillisecondsLikeTheRedisRecord() {
        Instant nanos = Instant.parse("2026-12-01T12:00:00.123456789Z");
        HoldService precise = new HoldService(catalog, store, new HoldProperties(TTL, 6), Clock.fixed(nanos, ZoneOffset.UTC), events, meters);
        when(catalog.findHoldableSeats(eq(SHOW), anyList())).thenReturn(Optional.of(new HoldableShow(SHOW,
                nanos.plus(Duration.ofDays(1)), List.of(seat(1, 100, ShowSeatStatus.AVAILABLE)))));
        when(store.tryHold(anyString(), anyLong(), anyLong(), anyList(), any(), any()))
                .thenAnswer(inv -> new HoldAttempt.Held(inv.getArgument(0)));

        Hold hold = precise.createHold(USER, SHOW, List.of(1L));

        assertThat(hold.expiresAt()).isEqualTo(Instant.parse("2026-12-01T12:08:00.123Z"));
        verify(store).tryHold(anyString(), anyLong(), anyLong(), anyList(), any(), eq(Instant.parse("2026-12-01T12:08:00.123Z")));
    }

    @Test
    void inputIsCheckedBeforeAnythingElse() {
        assertCode(() -> service.createHold(USER, SHOW, List.of()), InvalidRequestException.class, "NO_SEATS");
        assertCode(() -> service.createHold(USER, SHOW, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)),
                InvalidRequestException.class, "TOO_MANY_SEATS");
        assertCode(() -> service.createHold(USER, SHOW, List.of(1L, 1L)), InvalidRequestException.class, "DUPLICATE_SEATS");
        assertCode(() -> service.createHold(USER, SHOW, List.of(0L)), InvalidRequestException.class, "INVALID_SEAT_ID");
        verifyNoInteractions(catalog, store);
    }

    @Test
    void unknownOrUnbookableShowIsNotFound() {
        when(catalog.findHoldableSeats(eq(SHOW), anyList())).thenReturn(Optional.empty());

        assertCode(() -> service.createHold(USER, SHOW, List.of(1L)), NotFoundException.class, "NOT_FOUND");
        verifyNoInteractions(store);
    }

    @Test
    void aShowThatHasStartedCannotBeHeld() {
        showStartsIn(Duration.ZERO, seat(1, 100, ShowSeatStatus.AVAILABLE));

        assertCode(() -> service.createHold(USER, SHOW, List.of(1L)), ConflictException.class, "SHOW_STARTED");
        verifyNoInteractions(store);
    }

    @Test
    void seatsFromAnotherShowAreRejected() {
        showStartsIn(Duration.ofDays(1), seat(1, 100, ShowSeatStatus.AVAILABLE)); // seat 99 not in this show

        assertCode(() -> service.createHold(USER, SHOW, List.of(1L, 99L)), InvalidRequestException.class, "UNKNOWN_SEATS");
        verifyNoInteractions(store);
    }

    @Test
    void bookedSeatsAreRejectedWithoutTouchingRedis() {
        showStartsIn(Duration.ofDays(1), seat(1, 100, ShowSeatStatus.AVAILABLE), seat(2, 100, ShowSeatStatus.BOOKED));

        assertThatThrownBy(() -> service.createHold(USER, SHOW, List.of(1L, 2L)))
                .isInstanceOfSatisfying(SeatsUnavailableException.class, e -> assertThat(e.getSeatIds()).containsExactly(2L));
        verify(store, never()).tryHold(anyString(), anyLong(), anyLong(), anyList(), any(), any()); // only name() for the tag
        assertThat(holds(HoldService.OUTCOME_SEATS_UNAVAILABLE)).isEqualTo(1.0);
    }

    @Test
    void seatsHeldBySomeoneElseAreReportedAsUnavailable() {
        showStartsIn(Duration.ofDays(1), seat(1, 100, ShowSeatStatus.AVAILABLE), seat(2, 100, ShowSeatStatus.AVAILABLE));
        when(store.tryHold(anyString(), anyLong(), anyLong(), anyList(), any(), any()))
                .thenReturn(new HoldAttempt.SeatsTaken(List.of(2L)));

        assertThatThrownBy(() -> service.createHold(USER, SHOW, List.of(1L, 2L)))
                .isInstanceOfSatisfying(SeatsUnavailableException.class, e -> assertThat(e.getSeatIds()).containsExactly(2L));
        assertThat(holds(HoldService.OUTCOME_SEATS_TAKEN)).isEqualTo(1.0);
        assertThat(holds(HoldService.OUTCOME_HELD)).isZero();
    }

    @Test
    void aSecondHoldForTheSameShowIsAConflict() {
        showStartsIn(Duration.ofDays(1), seat(1, 100, ShowSeatStatus.AVAILABLE));
        when(store.tryHold(anyString(), anyLong(), anyLong(), anyList(), any(), any()))
                .thenReturn(new HoldAttempt.HoldExists("existing"));

        assertCode(() -> service.createHold(USER, SHOW, List.of(1L)), ConflictException.class, "HOLD_EXISTS");
        assertThat(holds(HoldService.OUTCOME_HOLD_EXISTS)).isEqualTo(1.0);
    }

    @Test
    void redisDownIs503NotA500() {
        showStartsIn(Duration.ofDays(1), seat(1, 100, ShowSeatStatus.AVAILABLE));
        when(store.tryHold(anyString(), anyLong(), anyLong(), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertCode(() -> service.createHold(USER, SHOW, List.of(1L)), ServiceUnavailableException.class, "HOLDS_UNAVAILABLE");
    }

    // ------------------------------------------------------------------ get / release

    @Test
    void ownerCanReadAndReleaseTheirHold() {
        HoldRecord record = new HoldRecord(HOLD_ID, USER, SHOW, List.of(1L), NOW.plus(TTL));
        when(store.find(HOLD_ID)).thenReturn(Optional.of(record));
        showStartsIn(Duration.ofDays(1), seat(1, 100, ShowSeatStatus.AVAILABLE));

        assertThat(service.getHold(USER, HOLD_ID).expiresAt()).isEqualTo(NOW.plus(TTL));
        service.releaseHold(USER, HOLD_ID);

        verify(store).release(record);
        verify(events, never()).publishEvent(any(Object.class)); // nothing was released (already expired)
    }

    @Test
    void releasingAHoldAnnouncesTheSeatsButReleasingAConvertedHoldDoesNot() {
        HoldRecord record = new HoldRecord(HOLD_ID, USER, SHOW, List.of(1L, 2L), NOW.plus(TTL));
        when(store.find(HOLD_ID)).thenReturn(Optional.of(record));
        when(store.release(record)).thenReturn(2);

        service.releaseConvertedHold(USER, HOLD_ID);   // seats are HELD by the booking now: no AVAILABLE event
        verify(events, never()).publishEvent(any(Object.class));

        service.releaseHold(USER, HOLD_ID);
        verify(events).publishEvent((Object) SeatsChangedEvent.of(SHOW, List.of(1L, 2L), ShowSeatStatus.AVAILABLE));
    }

    @Test
    void someoneElsesOrAnExpiredHoldIsNotFound() {
        String expired = "00000000-0000-4000-8000-000000000000";
        when(store.find(HOLD_ID)).thenReturn(Optional.of(new HoldRecord(HOLD_ID, USER + 1, SHOW, List.of(1L), NOW)));
        when(store.find(expired)).thenReturn(Optional.empty());

        assertCode(() -> service.releaseHold(USER, HOLD_ID), NotFoundException.class, "NOT_FOUND");
        assertCode(() -> service.getHold(USER, expired), NotFoundException.class, "NOT_FOUND");
        verify(store).find(HOLD_ID);      // reached the owner check (not rejected as malformed)
        verify(store).find(expired);
        verify(store, never()).release(any());
    }

    @Test
    void aMalformedHoldIdNeverReachesRedis() {
        assertCode(() -> service.getHold(USER, "h1"), NotFoundException.class, "NOT_FOUND");
        assertCode(() -> service.releaseHold(USER, "hold:*"), NotFoundException.class, "NOT_FOUND");
        verifyNoInteractions(store);
    }

    // ------------------------------------------------------------------ helpers

    /** seatlock.holds{strategy=redis-hold, outcome}; 0 if never counted. */
    private double holds(String outcome) {
        var counter = meters.find(HoldService.METER_HOLDS).tag("strategy", "redis-hold").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private void showStartsIn(Duration fromNow, HoldableShow.Seat... seats) {
        when(catalog.findHoldableSeats(eq(SHOW), anyList()))
                .thenReturn(Optional.of(new HoldableShow(SHOW, NOW.plus(fromNow), List.of(seats))));
    }

    private static HoldableShow.Seat seat(long id, long pricePaise, ShowSeatStatus status) {
        return new HoldableShow.Seat(id, "GOLD", "A", (int) id, pricePaise, status);
    }

    private static void assertCode(Runnable call, Class<? extends SeatLockException> type, String code) {
        assertThatThrownBy(call::run).isInstanceOf(type).extracting("code").isEqualTo(code);
    }
}
