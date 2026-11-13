package com.seatlock.catalog.application;

import static java.util.stream.Collectors.groupingBy;

import com.seatlock.catalog.api.ShowSeatMapResponse;
import com.seatlock.catalog.api.ShowSeatMapResponse.SeatView;
import com.seatlock.catalog.api.ShowSeatMapResponse.SectionPrice;
import com.seatlock.catalog.api.ShowSeatMapResponse.VenueRef;
import com.seatlock.catalog.application.ShowQueryRepository.ShowHeader;
import com.seatlock.common.exception.NotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ShowCatalogService {

    private final ShowQueryRepository showQueries;

    public ShowCatalogService(ShowQueryRepository showQueries) {
        this.showQueries = showQueries;
    }

    /** Seat map for a bookable show. 404 if unknown, cancelled, or its event isn't published. */
    public ShowSeatMapResponse getSeatMap(long showId) {
        ShowHeader show = showQueries.findBookableShow(showId)
                .orElseThrow(() -> new NotFoundException("Show", showId));
        List<SeatView> seats = showQueries.seatsForShow(showId);

        int gridRows = seats.stream().mapToInt(SeatView::gridRow).max().orElse(-1) + 1;
        int gridCols = seats.stream().mapToInt(SeatView::gridCol).max().orElse(-1) + 1;

        // Price legend: one entry per section (lowest price in it), most expensive first.
        Map<String, List<SeatView>> bySection = seats.stream().collect(groupingBy(SeatView::section));
        List<SectionPrice> sections = bySection.entrySet().stream()
                .map(e -> new SectionPrice(
                        e.getKey(),
                        e.getValue().stream().mapToLong(SeatView::pricePaise).min().orElse(0),
                        e.getValue().size()))
                .sorted(Comparator.comparingLong(SectionPrice::pricePaise).reversed())
                .toList();

        return new ShowSeatMapResponse(show.showId(), show.eventId(), show.eventTitle(), show.startsAt(),
                new VenueRef(show.venueId(), show.venueName(), show.city()),
                gridRows, gridCols, sections, seats);
    }
}