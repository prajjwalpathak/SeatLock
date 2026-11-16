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
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class HoldServiceTest {

    private static final long USER = 7L;
    private static final long SHOW = 42L;
    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(8);
    private static final String HOLD_ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b";

    private final ShowCatalogService catalog = mock(ShowCatalogService.class);
    private final RedisHoldStore store = mock(RedisHoldStore.class);
    private final HoldService service = new HoldService(catalog, store, new HoldProperties(TTL, 6),
            Clock.fixed(NOW, ZoneOffset.UTC));

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
        verifyNoInteractions(store);
    }

    @Test
    void seatsHeldBySomeoneElseAreReportedAsUnavailable() {
        showStartsIn(Duration.ofDays(1), seat(1, 100, ShowSeatStatus.AVAILABLE), seat(2, 100, ShowSeatStatus.AVAILABLE));
        when(store.tryHold(anyString(), anyLong(), anyLong(), anyList(), any(), any()))
                .thenReturn(new HoldAttempt.SeatsTaken(List.of(2L)));

        assertThatThrownBy(() -> service.createHold(USER, SHOW, List.of(1L, 2L)))
                .isInstanceOfSatisfying(SeatsUnavailableException.class, e -> assertThat(e.getSeatIds()).containsExactly(2L));
    }

    @Test
    void aSecondHoldForTheSameShowIsAConflict() {
        showStartsIn(Duration.ofDays(1), seat(1, 100, ShowSeatStatus.AVAILABLE));
        when(store.tryHold(anyString(), anyLong(), anyLong(), anyList(), any(), any()))
                .thenReturn(new HoldAttempt.HoldExists("existing"));

        assertCode(() -> service.createHold(USER, SHOW, List.of(1L)), ConflictException.class, "HOLD_EXISTS");
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
