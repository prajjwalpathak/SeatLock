package com.seatlock.booking.application;

import com.seatlock.booking.domain.Booking;
import com.seatlock.booking.domain.BookingRepository;
import com.seatlock.booking.domain.BookingSeat;
import com.seatlock.booking.hold.Hold;
import com.seatlock.booking.hold.HoldService;
import com.seatlock.booking.hold.SeatsUnavailableException;
import com.seatlock.catalog.application.HoldableShow;
import com.seatlock.catalog.application.ShowCatalogService;
import com.seatlock.catalog.application.ShowSeatStatusService;
import com.seatlock.common.exception.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hold → booking ("Proceed to pay"). Redis handled the contended part; from here Postgres is the source of truth.
 * <ol>
 *   <li>An existing booking for this holdId is returned as is (conversion is naturally idempotent: hold_id is UNIQUE).</li>
 *   <li>The live hold is read (owner only, else 404) for seats, prices and expiry.</li>
 *   <li>One transaction: show_seats AVAILABLE → HELD (compare-and-set; all or nothing), booking + booking_seats
 *       (the partial unique index rejects any seat already in a live booking).</li>
 *   <li>After commit, the Redis hold is released: the DB now says HELD, so the seat map stays correct.</li>
 * </ol>
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final HoldService holds;
    private final ShowCatalogService catalog;
    private final ShowSeatStatusService seatStatus;
    private final TransactionTemplate tx;

    public BookingService(BookingRepository bookings, HoldService holds, ShowCatalogService catalog,
                          ShowSeatStatusService seatStatus, TransactionTemplate tx) {
        this.bookings = bookings;
        this.holds = holds;
        this.catalog = catalog;
        this.seatStatus = seatStatus;
        this.tx = tx;
    }

    /** Result of a conversion: the booking, and whether this call created it (201) or found it (200). */
    public record Conversion(BookingView booking, boolean created) {}

    public Conversion createFromHold(long userId, String holdId) {
        UUID hid = parseUuid(holdId).orElseThrow(() -> new NotFoundException("Hold", "(invalid id)"));

        Optional<Conversion> existing = existingFor(userId, hid);
        if (existing.isPresent()) {
            return existing.get();
        }

        Hold hold;
        try {
            hold = holds.getHold(userId, holdId); // 404 if expired, released or someone else's
        } catch (NotFoundException e) {
            // A concurrent call may have just converted it (and released the Redis hold): answer with that booking.
            return existingFor(userId, hid).orElseThrow(() -> e);
        }

        Booking booking;
        try {
            booking = tx.execute(status -> insert(userId, hid, hold));
        } catch (SeatsUnavailableException | DataIntegrityViolationException e) {
            // Lost a race against a concurrent conversion of the SAME hold? Then its booking is the answer.
            Optional<Conversion> winner = existingFor(userId, hid);
            if (winner.isPresent()) {
                return winner.get();
            }
            if (e instanceof DataIntegrityViolationException dive
                    && String.valueOf(dive.getMostSpecificCause().getMessage()).contains("ux_booking_seats_live_seat")) {
                // Redis said free but a live booking already has a seat (Redis lost data?): the DB guard wins.
                throw new SeatsUnavailableException(hold.seats().stream().map(Hold.HeldSeat::seatId).toList());
            }
            throw e;
        }

        releaseRedisHold(userId, holdId);
        return new Conversion(view(booking, hold), true);
    }

    // ------------------------------------------------------------------ internals

    private Booking insert(long userId, UUID holdId, Hold hold) {
        List<Long> seatIds = hold.seats().stream().map(Hold.HeldSeat::seatId).toList();
        int moved = seatStatus.markHeld(hold.showId(), seatIds);
        if (moved != seatIds.size()) {
            // Some seat is no longer AVAILABLE in the DB (booked meanwhile). Throwing rolls everything back.
            throw new SeatsUnavailableException(seatIds);
        }
        Booking booking = new Booking(userId, hold.showId(), holdId, hold.expiresAt());
        hold.seats().forEach(s -> booking.addSeat(s.seatId(), s.pricePaise()));
        return bookings.saveAndFlush(booking); // flush now so constraint violations surface inside this call
    }

    private Optional<Conversion> existingFor(long userId, UUID holdId) {
        return bookings.findByHoldId(holdId)
                .map(b -> {
                    if (b.getUserId() != userId) {
                        throw new NotFoundException("Hold", holdId); // never reveal another user's booking
                    }
                    return new Conversion(view(b), false);
                });
    }

    private void releaseRedisHold(long userId, String holdId) {
        try {
            holds.releaseHold(userId, holdId);
        } catch (RuntimeException e) {
            // Harmless: the DB already says HELD, and the Redis keys expire with the hold's TTL anyway.
            log.warn("Booked hold {} but couldn't release its Redis keys ({}); they will expire", holdId, e.toString());
        }
    }

    /** View for a freshly created booking: labels come from the hold we just converted. */
    private BookingView view(Booking b, Hold hold) {
        Map<Long, Hold.HeldSeat> byId = hold.seats().stream()
                .collect(Collectors.toMap(Hold.HeldSeat::seatId, Function.identity()));
        return toView(b, seat -> {
            Hold.HeldSeat h = byId.get(seat.getSeatId());
            return new BookingView.Seat(h.seatId(), h.section(), h.rowLabel(), h.seatNumber(), seat.getPricePaise());
        });
    }

    /** View for a stored booking: labels come from the catalog (one query); prices from the booking. */
    BookingView view(Booking b) {
        List<Long> seatIds = b.getSeats().stream().map(BookingSeat::getSeatId).toList();
        Map<Long, HoldableShow.Seat> labels = catalog.findSeatLabels(b.getShowId(), seatIds).stream()
                .collect(Collectors.toMap(HoldableShow.Seat::seatId, Function.identity()));
        return toView(b, seat -> {
            HoldableShow.Seat l = labels.get(seat.getSeatId());
            return new BookingView.Seat(seat.getSeatId(), l.section(), l.rowLabel(), l.seatNumber(), seat.getPricePaise());
        });
    }

    private static BookingView toView(Booking b, Function<BookingSeat, BookingView.Seat> seat) {
        return new BookingView(b.getPublicId(), b.getShowId(), b.getStatus(),
                b.getSeats().stream().map(seat).toList(), b.getTotalPaise(), b.getCurrency(),
                b.getExpiresAt(), b.getConfirmedAt(), b.getCancelledAt());
    }

    static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
