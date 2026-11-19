package com.seatlock.booking.application;

import com.seatlock.catalog.application.ShowCatalogService;
import com.seatlock.catalog.application.ShowLabel;
import com.seatlock.common.web.PageResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * "My bookings": the caller's bookings, newest first, with their show's event, time and venue.
 * Two booking queries + one catalog query per page (module rule: show data comes from catalog's public service).
 * Not @Transactional: three independent reads; a booking created between them only shifts the next page.
 */
@Service
public class MyBookingsService {

    private final MyBookingsQuery query;
    private final ShowCatalogService catalog;

    public MyBookingsService(MyBookingsQuery query, ShowCatalogService catalog) {
        this.query = query;
        this.catalog = catalog;
    }

    public PageResponse<MyBookingSummary> list(long userId, int page, int size) {
        List<MyBookingsQuery.Row> rows = query.page(userId, page, size);
        long total = query.count(userId);
        Map<Long, ShowLabel> shows = catalog.findShowLabels(rows.stream().map(MyBookingsQuery.Row::showId).distinct().toList());
        List<MyBookingSummary> items = rows.stream().map(r -> {
            ShowLabel show = shows.get(r.showId()); // always present (FK); null-safe anyway
            return new MyBookingSummary(r.publicId(), r.status(), r.showId(),
                    show == null ? null : show.eventId(),
                    show == null ? null : show.eventTitle(),
                    show == null ? null : show.startsAt(),
                    show == null ? null : show.venueName(),
                    show == null ? null : show.city(),
                    r.seatCount(), r.totalPaise(), r.currency(), r.expiresAt(), r.createdAt());
        }).toList();
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }
}
