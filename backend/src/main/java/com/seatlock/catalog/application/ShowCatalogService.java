package com.seatlock.catalog.application;

import static java.util.stream.Collectors.groupingBy;

import com.seatlock.catalog.api.ShowSeatMapResponse;
import com.seatlock.catalog.api.ShowSeatMapResponse.SeatView;
import com.seatlock.catalog.api.ShowSeatMapResponse.SectionPrice;
import com.seatlock.catalog.api.ShowSeatMapResponse.VenueRef;
import com.seatlock.catalog.application.ShowQueryRepository.ShowHeader;
import com.seatlock.catalog.domain.ShowSeatStatus;
import com.seatlock.common.exception.NotFoundException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Seat map = layout + prices + status from Postgres, overlaid with live holds from Redis.
 * <p>Deliberately <b>not</b> {@code @Transactional}: each query borrows a connection only for itself,
 * so no DB connection is held while we wait on Redis. If Redis hangs (up to the 500 ms timeout),
 * a transaction around the call would pin one Hikari connection per request and could exhaust the pool.</p>
 */
@Service
public class ShowCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ShowCatalogService.class);

    private final ShowQueryRepository showQueries;
    private final SeatHoldLookup holds;

    public ShowCatalogService(ShowQueryRepository showQueries, SeatHoldLookup holds) {
        this.showQueries = showQueries;
        this.holds = holds;
    }

    /** Seat map for a bookable show. 404 if unknown, cancelled, or its event isn't published. */
    public ShowSeatMapResponse getSeatMap(long showId) {
        ShowHeader show = showQueries.findBookableShow(showId)
                .orElseThrow(() -> new NotFoundException("Show", showId));
        List<SeatView> dbSeats = showQueries.seatsForShow(showId);

        LiveHolds live = readHolds(showId, dbSeats);
        List<SeatView> seats = live.held().isEmpty()
                ? dbSeats
                : dbSeats.stream().map(seat -> overlay(seat, live.held())).toList();

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
                gridRows, gridCols, sections, seats, live.available());
    }

    /**
     * For the booking module: the bookable show (SCHEDULED, event PUBLISHED) and those of {@code seatIds}
     * that belong to it, with price and DB status. Empty if the show isn't bookable.
     */
    public Optional<HoldableShow> findHoldableSeats(long showId, Collection<Long> seatIds) {
        return showQueries.findBookableShow(showId)
                .map(show -> new HoldableShow(show.showId(), show.startsAt(),
                        seatIds.isEmpty() ? List.of() : showQueries.seatsById(showId, seatIds)));
    }

    private record LiveHolds(Set<Long> held, boolean available) {}

    /** Redis down or slow → degrade to the DB view; the seat map must never fail because of holds. */
    private LiveHolds readHolds(long showId, List<SeatView> seats) {
        // Only seats the DB still calls AVAILABLE can be "held"; BOOKED seats need no lookup.
        List<Long> candidates = seats.stream()
                .filter(s -> s.status() == ShowSeatStatus.AVAILABLE)
                .map(SeatView::seatId)
                .toList();
        try {
            return new LiveHolds(holds.heldSeatIds(showId, candidates), true);
        } catch (DataAccessException e) {
            log.warn("Seat holds unavailable for show {}; serving database status only ({})", showId, e.toString());
            return new LiveHolds(Set.of(), false);
        }
    }

    /** Postgres decides BOOKED (durable, wins); Redis can only turn AVAILABLE into HELD. */
    private static SeatView overlay(SeatView seat, Set<Long> held) {
        if (seat.status() != ShowSeatStatus.AVAILABLE || !held.contains(seat.seatId())) {
            return seat;
        }
        return new SeatView(seat.seatId(), seat.section(), seat.rowLabel(), seat.seatNumber(),
                seat.gridRow(), seat.gridCol(), seat.aisle(), seat.pricePaise(), ShowSeatStatus.HELD);
    }
}
