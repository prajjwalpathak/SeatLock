package com.seatlock.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatlock.catalog.api.ShowSeatMapResponse;
import com.seatlock.catalog.api.ShowSeatMapResponse.SeatView;
import com.seatlock.catalog.application.ShowQueryRepository.ShowHeader;
import com.seatlock.catalog.domain.ShowSeatStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/** Merge rules for the seat map: DB = durable BOOKED, Redis = ephemeral HELD, Redis down = DB view. */
class ShowCatalogServiceTest {

    private static final long SHOW = 7L;

    private final ShowQueryRepository queries = mock(ShowQueryRepository.class);
    private final SeatHoldLookup holds = mock(SeatHoldLookup.class);
    private final ShowCatalogService service = new ShowCatalogService(queries, holds);

    @BeforeEach
    void seatsInDb() {
        when(queries.findBookableShow(SHOW)).thenReturn(Optional.of(
                new ShowHeader(SHOW, Instant.parse("2026-12-01T14:00:00Z"), 1L, "Show", 2L, "Hall", "Delhi")));
        when(queries.seatsForShow(SHOW)).thenReturn(List.of(
                seat(1, ShowSeatStatus.AVAILABLE),
                seat(2, ShowSeatStatus.AVAILABLE),
                seat(3, ShowSeatStatus.BOOKED)));
    }

    @Test
    void aHeldSeatInRedisIsShownAsHeld() {
        when(holds.heldSeatIds(eq(SHOW), anyCollection())).thenReturn(Set.of(2L));

        ShowSeatMapResponse map = service.getSeatMap(SHOW);

        assertThat(statuses(map)).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, ShowSeatStatus.AVAILABLE, 2L, ShowSeatStatus.HELD, 3L, ShowSeatStatus.BOOKED));
        assertThat(map.holdsLive()).isTrue();
    }

    @Test
    void bookedInTheDatabaseWinsOverAStaleHoldKey() {
        // e.g. confirmed a moment ago, hold key not yet deleted
        when(holds.heldSeatIds(eq(SHOW), anyCollection())).thenReturn(Set.of(3L));

        assertThat(statuses(service.getSeatMap(SHOW))).containsEntry(3L, ShowSeatStatus.BOOKED);
    }

    @Test
    void onlyAvailableSeatsAreLookedUpInRedis() {
        when(holds.heldSeatIds(eq(SHOW), anyCollection())).thenReturn(Set.of());

        service.getSeatMap(SHOW);

        verify(holds).heldSeatIds(SHOW, List.of(1L, 2L)); // seat 3 is BOOKED: no need to ask
    }

    @Test
    void redisDownDegradesToTheDatabaseViewInsteadOfFailing() {
        when(holds.heldSeatIds(anyLong(), anyCollection()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        ShowSeatMapResponse map = service.getSeatMap(SHOW);

        assertThat(map.holdsLive()).isFalse();
        assertThat(map.seats()).hasSize(3);
        assertThat(statuses(map)).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, ShowSeatStatus.AVAILABLE, 2L, ShowSeatStatus.AVAILABLE, 3L, ShowSeatStatus.BOOKED));
    }

    private static SeatView seat(long id, ShowSeatStatus status) {
        return new SeatView(id, "GOLD", "A", (int) id, 0, (int) id, false, 50_000, status);
    }

    private static Map<Long, ShowSeatStatus> statuses(ShowSeatMapResponse map) {
        return map.seats().stream().collect(Collectors.toMap(SeatView::seatId, SeatView::status));
    }
}
