package com.seatlock.booking.hold;

import com.seatlock.booking.hold.HoldAttempt.Held;
import com.seatlock.booking.hold.HoldAttempt.HoldExists;
import com.seatlock.booking.hold.HoldAttempt.SeatsTaken;
import com.seatlock.catalog.application.HoldableShow;
import com.seatlock.catalog.application.ShowCatalogService;
import com.seatlock.catalog.domain.ShowSeatStatus;
import com.seatlock.catalog.events.SeatsChangedEvent;
import com.seatlock.common.exception.ConflictException;
import com.seatlock.common.exception.InvalidRequestException;
import com.seatlock.common.exception.NotFoundException;
import com.seatlock.common.exception.ServiceUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Seat holds: validate against the database, then claim atomically in Redis (hold_seats.lua).
 * <p>Order matters: cheap input checks → DB checks (show bookable and not started, seats belong to it,
 * not BOOKED) → one atomic Redis call. The DB checks reject obvious failures early; correctness against
 * concurrent users comes only from the Lua script (and, at booking time, the DB unique index).</p>
 * <p>Not {@code @Transactional}: the catalog reads are single queries, and no DB connection should be
 * held across the Redis call (same reasoning as the seat map).</p>
 * <p>Successful holds and user releases publish a {@link SeatsChangedEvent} (no transaction here, so it is
 * broadcast at once). Expiry by TTL publishes nothing: the HELD event carries {@code holdExpiresAt}.</p>
 */
@Service
@EnableConfigurationProperties(HoldProperties.class)
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);
    /** Counter {@code seatlock.holds{strategy, outcome}}; everything but {@code held} is a conflict. */
    static final String METER_HOLDS = "seatlock.holds";
    static final String OUTCOME_HELD = "held";
    /** Seats already HELD/BOOKED in Postgres (pre-check, before any claim). */
    static final String OUTCOME_SEATS_UNAVAILABLE = "seats_unavailable";
    /** Lost the claim to a concurrent hold (Lua / upsert said taken): the contention we care about. */
    static final String OUTCOME_SEATS_TAKEN = "seats_taken";
    static final String OUTCOME_HOLD_EXISTS = "hold_exists";

    private static final Pattern HOLD_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ShowCatalogService catalog;
    private final SeatLockingStrategy store;
    private final HoldProperties props;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;

    public HoldService(ShowCatalogService catalog, SeatLockingStrategy store, HoldProperties props, Clock clock,
                       ApplicationEventPublisher events, MeterRegistry meters) {
        this.catalog = catalog;
        this.store = store;
        this.props = props;
        this.clock = clock;
        this.events = events;
        this.meters = meters;
    }

    public Hold createHold(long userId, long showId, List<Long> seatIds) {
        checkSeatList(seatIds);

        HoldableShow show = catalog.findHoldableSeats(showId, seatIds)
                .orElseThrow(() -> new NotFoundException("Show", showId));
        Instant now = clock.instant();
        if (!show.startsAt().isAfter(now)) {
            throw new ConflictException("SHOW_STARTED", "This show has already started");
        }
        Map<Long, HoldableShow.Seat> byId = show.seats().stream()
                .collect(Collectors.toMap(HoldableShow.Seat::seatId, Function.identity()));
        List<Long> unknown = seatIds.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("UNKNOWN_SEATS", "Seats " + unknown + " are not part of show " + showId);
        }
        List<Long> booked = seatIds.stream()
                .filter(id -> byId.get(id).status() != ShowSeatStatus.AVAILABLE)
                .toList();
        if (!booked.isEmpty()) {
            count(OUTCOME_SEATS_UNAVAILABLE);
            throw new SeatsUnavailableException(booked);
        }

        String holdId = UUID.randomUUID().toString();
        // Millisecond precision: that's what the Redis record stores, so POST, GET and the booking all agree.
        Instant expiresAt = now.plus(props.ttl()).truncatedTo(ChronoUnit.MILLIS);
        HoldAttempt attempt = withRedis(() -> store.tryHold(holdId, userId, showId, seatIds, props.ttl(), expiresAt));
        return switch (attempt) {
            case Held held -> {
                count(OUTCOME_HELD);
                events.publishEvent(new SeatsChangedEvent(showId, seatIds, ShowSeatStatus.HELD, expiresAt));
                yield toHold(held.holdId(), showId, seatIds, byId, expiresAt);
            }
            case SeatsTaken taken -> {
                count(OUTCOME_SEATS_TAKEN);
                throw new SeatsUnavailableException(taken.seatIds());
            }
            case HoldExists exists -> {
                count(OUTCOME_HOLD_EXISTS);
                throw new HoldExistsException(exists.existingHoldId());
            }
        };
    }

    /** The caller's live hold. Someone else's or an expired hold is 404 (no way to probe other users' holds). */
    public Hold getHold(long userId, String holdId) {
        HoldRecord record = ownedRecord(userId, holdId);
        HoldableShow show = catalog.findHoldableSeats(record.showId(), record.seatIds())
                .orElseThrow(() -> new NotFoundException("Hold", holdId));
        Map<Long, HoldableShow.Seat> byId = show.seats().stream()
                .collect(Collectors.toMap(HoldableShow.Seat::seatId, Function.identity()));
        return toHold(holdId, record.showId(), record.seatIds(), byId, record.expiresAt());
    }

    /** Releases the caller's hold; the seats are free again immediately (and viewers are told so). */
    public void releaseHold(long userId, String holdId) {
        HoldRecord record = ownedRecord(userId, holdId);
        int released = withRedis(() -> store.release(record));
        log.debug("Released hold {} ({} seats)", holdId, released);
        if (released > 0) {
            events.publishEvent(SeatsChangedEvent.of(record.showId(), record.seatIds(), ShowSeatStatus.AVAILABLE));
        }
    }

    /**
     * Drops the hold's keys after it became a booking. Publishes nothing: the seats are HELD in the DB now
     * (announced by the booking's own commit), so an AVAILABLE event here would be wrong.
     */
    public void releaseConvertedHold(long userId, String holdId) {
        HoldRecord record = ownedRecord(userId, holdId);
        withRedis(() -> store.release(record));
    }

    // ------------------------------------------------------------------ helpers

    /** Two low-cardinality tags only (2 strategies × 4 outcomes): never a show, seat or user id. */
    private void count(String outcome) {
        Counter.builder(METER_HOLDS)
                .description("Hold attempts by outcome; anything but 'held' is a conflict")
                .tag("strategy", store.name())
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    private void checkSeatList(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new InvalidRequestException("NO_SEATS", "Select at least one seat");
        }
        if (seatIds.size() > props.maxSeats()) {
            throw new InvalidRequestException("TOO_MANY_SEATS", "You can hold up to " + props.maxSeats() + " seats");
        }
        if (seatIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new InvalidRequestException("INVALID_SEAT_ID", "Seat IDs must be positive numbers");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new InvalidRequestException("DUPLICATE_SEATS", "Each seat can be selected only once");
        }
    }

    private HoldRecord ownedRecord(long userId, String holdId) {
        if (holdId == null || !HOLD_ID.matcher(holdId).matches()) {
            throw new NotFoundException("Hold", "(invalid id)"); // never build a Redis key from arbitrary input
        }
        HoldRecord record = withRedis(() -> store.find(holdId))
                .orElseThrow(() -> new NotFoundException("Hold", holdId));
        if (record.userId() != userId) {
            throw new NotFoundException("Hold", holdId);
        }
        return record;
    }

    private static Hold toHold(String holdId, long showId, List<Long> seatIds,
                               Map<Long, HoldableShow.Seat> byId, Instant expiresAt) {
        List<Hold.HeldSeat> seats = seatIds.stream()
                .map(byId::get)
                .map(s -> new Hold.HeldSeat(s.seatId(), s.section(), s.rowLabel(), s.seatNumber(), s.pricePaise()))
                .toList();
        long total = 0;
        for (Hold.HeldSeat seat : seats) {
            total = Math.addExact(total, seat.pricePaise());
        }
        return new Hold(holdId, showId, seats, total, expiresAt);
    }

    /** Unlike the seat map, holds can't degrade: no Redis means no holds → 503, not 500. */
    private <T> T withRedis(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            log.warn("Seat holds unavailable ({})", e.toString());
            throw new ServiceUnavailableException("HOLDS_UNAVAILABLE",
                    "Seat holds are temporarily unavailable. Please try again in a moment.");
        }
    }
}
