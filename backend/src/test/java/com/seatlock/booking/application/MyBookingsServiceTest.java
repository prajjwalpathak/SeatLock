package com.seatlock.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.seatlock.booking.domain.BookingStatus;
import com.seatlock.catalog.application.ShowCatalogService;
import com.seatlock.catalog.application.ShowLabel;
import com.seatlock.common.web.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Assembly of "My bookings" rows: show labels joined in, page arithmetic, and a missing label never breaks the list. */
class MyBookingsServiceTest {

    private static final Instant T = Instant.parse("2026-12-01T12:00:00Z");

    private final MyBookingsQuery query = mock(MyBookingsQuery.class);
    private final ShowCatalogService catalog = mock(ShowCatalogService.class);
    private final MyBookingsService service = new MyBookingsService(query, catalog);

    private static MyBookingsQuery.Row row(long showId) {
        return new MyBookingsQuery.Row(UUID.randomUUID(), BookingStatus.HELD, showId, 2, 50_000, "INR", T, T);
    }

    @Test
    void joinsShowLabelsAndComputesPages() {
        when(query.page(7, 0, 2)).thenReturn(List.of(row(10), row(10)));
        when(query.count(7)).thenReturn(5L);
        when(catalog.findShowLabels(List.of(10L)))   // distinct ids: one lookup per show, not per booking
                .thenReturn(Map.of(10L, new ShowLabel(10, T, 3, "Neon Bazaar", "Hall 1", "Delhi")));

        PageResponse<MyBookingSummary> page = service.list(7, 0, 2);

        assertThat(page.totalItems()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);   // 5 items, 2 per page
        assertThat(page.items()).extracting(MyBookingSummary::eventTitle).containsOnly("Neon Bazaar");
        assertThat(page.items().getFirst().venueName()).isEqualTo("Hall 1");
    }

    @Test
    void aMissingShowLabelLeavesThoseFieldsEmptyInsteadOfFailing() {
        when(query.page(7, 0, 20)).thenReturn(List.of(row(99)));
        when(query.count(7)).thenReturn(1L);
        when(catalog.findShowLabels(anyCollection())).thenReturn(Map.of());

        MyBookingSummary item = service.list(7, 0, 20).items().getFirst();

        assertThat(item.showId()).isEqualTo(99);
        assertThat(item.eventTitle()).isNull();
        assertThat(item.eventId()).isNull();
        assertThat(item.showStartsAt()).isNull();
        assertThat(item.venueName()).isNull();
        assertThat(item.city()).isNull();
    }

    @Test
    void noBookingsIsAnEmptyFirstPage() {
        when(query.page(7, 0, 20)).thenReturn(List.of());
        when(query.count(7)).thenReturn(0L);
        when(catalog.findShowLabels(anyCollection())).thenReturn(Map.of());

        PageResponse<MyBookingSummary> page = service.list(7, 0, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalPages()).isZero();
    }
}
