package com.seatlock.assistant.seatfinder;

import com.seatlock.catalog.api.ShowSeatMapResponse;
import com.seatlock.catalog.application.ShowCatalogService;
import com.seatlock.catalog.domain.ShowSeatStatus;
import com.seatlock.common.exception.InvalidRequestException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Seat Finder without AI: reads the live seat map (catalog's public service: DB statuses + Redis holds) and
 * runs {@link SeatFinder}. Read-only and not transactional, like the seat map itself. The AI path (W7·D3) only
 * turns free text into {@link SeatPreferences} and then calls {@link #find} too.
 */
@Service
public class SeatFinderService {

    private final ShowCatalogService catalog;
    private final SeatFinder finder = new SeatFinder();
    private final int maxSeats;

    public SeatFinderService(ShowCatalogService catalog, @Value("${seatlock.holds.max-seats}") int maxSeats) {
        this.catalog = catalog;
        this.maxSeats = maxSeats;
    }

    /** {@code holdsLive = false}: Redis couldn't be read, so held seats may be suggested (holding them will fail). */
    public record Result(long showId, boolean holdsLive, List<SeatOption> options) {}

    public Result find(long showId, SeatPreferences prefs) {
        if (prefs.count() > maxSeats) {
            throw new InvalidRequestException("TOO_MANY_SEATS", "You can hold up to " + maxSeats + " seats");
        }
        ShowSeatMapResponse map = catalog.getSeatMap(showId);   // 404 for unknown / unbookable shows

        Set<String> known = map.sections().stream().map(ShowSeatMapResponse.SectionPrice::section)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> unknown = prefs.sections().stream().filter(s -> !known.contains(s))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!unknown.isEmpty()) {
            throw new UnknownSectionException(unknown, known);
        }

        SeatLayout layout = new SeatLayout(map.seats().stream()
                .map(s -> new SeatLayout.Seat(s.seatId(), s.section(), s.rowLabel(), s.seatNumber(), s.gridRow(),
                        s.gridCol(), s.aisle(), s.pricePaise(), s.status() == ShowSeatStatus.AVAILABLE))
                .toList());
        return new Result(showId, map.holdsLive(), finder.find(layout, prefs));
    }

    /** 400 {@code UNKNOWN_SECTION} with the sections this venue has. */
    public static class UnknownSectionException extends InvalidRequestException {

        private final Set<String> unknown;
        private final Set<String> available;

        UnknownSectionException(Set<String> unknown, Set<String> available) {
            super("UNKNOWN_SECTION", "Unknown section " + unknown + "; this venue has " + available);
            this.unknown = unknown;
            this.available = available;
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("sections", List.copyOf(available), "unknown", List.copyOf(unknown));
        }
    }
}
