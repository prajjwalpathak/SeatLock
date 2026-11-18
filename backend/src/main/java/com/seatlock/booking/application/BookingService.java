package com.seatlock.booking.application;

import com.seatlock.booking.domain.Booking;
import com.seatlock.booking.domain.BookingRepository;
import com.seatlock.booking.domain.BookingSeat;
import com.seatlock.booking.domain.BookingStatus;
import com.seatlock.booking.hold.Hold;
import com.seatlock.booking.hold.HoldService;
import com.seatlock.booking.hold.SeatsUnavailableException;
import com.seatlock.catalog.application.HoldableShow;
import com.seatlock.catalog.application.ShowCatalogService;
import com.seatlock.catalog.application.ShowSeatStatusService;
import com.seatlock.common.exception.NotFoundException;
import java.time.Clock;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
    private final Clock clock;

    public BookingService(BookingRepository bookings, HoldService holds, ShowCatalogService catalog,
                          ShowSeatStatusService seatStatus, TransactionTemplate tx, Clock clock) {
        this.bookings = bookings;
        this.holds = holds;
        this.catalog = catalog;
        this.seatStatus = seatStatus;
        this.tx = tx;
        this.clock = clock;
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

    /** The caller's booking. Someone else's or an unknown one is 404 (no probing). */
    public BookingView getBooking(long userId, String publicId) {
        UUID id = parseUuid(publicId).orElseThrow(() -> new NotFoundException("Booking", "(invalid id)"));
        return bookings.findByPublicId(id)
                .filter(b -> b.getUserId() == userId)
                .map(this::view)
                .orElseThrow(() -> new NotFoundException("Booking", id));
    }

    /**
     * User cancels an unpaid booking (HELD / PAYMENT_PENDING → CANCELLED) and the seats are freed at once.
     * Already CANCELLED → returned as is (a retry is harmless). EXPIRED / CONFIRMED / REFUNDED → 409 from the
     * state machine (paid bookings are refunded instead, W4).
     */
    @Transactional
    public BookingView cancel(long userId, String publicId) {
        UUID id = parseUuid(publicId).orElseThrow(() -> new NotFoundException("Booking", "(invalid id)"));
        Booking booking = bookings.findForUpdateByPublicId(id)
                .filter(b -> b.getUserId() == userId)
                .orElseThrow(() -> new NotFoundException("Booking", id));
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return view(booking);
        }
        List<Long> seatIds = booking.getSeats().stream().map(BookingSeat::getSeatId).toList();
        booking.cancel(clock.instant());
        seatStatus.markAvailable(booking.getShowId(), seatIds);
        return view(booking);
    }

    // ------------------------------------------------------------------ payment module API

    /**
     * Read-only check before a gateway call: the caller's booking exists (else 404), is HELD or PAYMENT_PENDING
     * (else 409 INVALID_BOOKING_TRANSITION), its deadline hasn't passed (else 409 BOOKING_EXPIRED), and its
     * server-side amount is consistent. No lock: {@link #startPayment} repeats the checks under one.
     */
    public PayableBooking checkPayable(long userId, String publicId) {
        UUID id = parseUuid(publicId).orElseThrow(() -> new NotFoundException("Booking", "(invalid id)"));
        Booking booking = bookings.findByPublicId(id)
                .filter(b -> b.getUserId() == userId)
                .orElseThrow(() -> new NotFoundException("Booking", id));
        return payable(booking);
    }

    /**
     * Locks the booking row (PESSIMISTIC_WRITE), repeats {@link #checkPayable}'s checks and moves HELD →
     * PAYMENT_PENDING (a retry on PAYMENT_PENDING stays as is). Must run inside the caller's transaction, so the
     * payment row is written under the same lock: concurrent attempts for one booking are serialized here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PayableBooking startPayment(long userId, String publicId) {
        UUID id = parseUuid(publicId).orElseThrow(() -> new NotFoundException("Booking", "(invalid id)"));
        Booking booking = bookings.findForUpdateByPublicId(id)
                .filter(b -> b.getUserId() == userId)
                .orElseThrow(() -> new NotFoundException("Booking", id));
        PayableBooking payable = payable(booking);
        if (booking.getStatus() == BookingStatus.HELD) {
            booking.startPayment(clock.instant());
        }
        return new PayableBooking(payable.bookingId(), payable.publicId(), booking.getStatus(),
                payable.amountPaise(), payable.currency(), payable.expiresAt());
    }

    /** What happened when a captured payment tried to confirm its booking. */
    public enum ConfirmResult {
        /** PAYMENT_PENDING → CONFIRMED now; seats BOOKED. */
        CONFIRMED,
        /** Already CONFIRMED (duplicate callback/webhook): nothing changed. */
        ALREADY_CONFIRMED,
        /** EXPIRED / CANCELLED / ... : the seats are no longer ours, so the money must go back (refund, W4·D4). */
        NOT_CONFIRMABLE
    }

    /**
     * Payment module API: the booking's payment was captured. Locks the booking row (the expiry sweeper and cancel
     * lock it too, so exactly one of them wins), then PAYMENT_PENDING → CONFIRMED and show_seats HELD → BOOKED.
     * A payment that lands after {@code expiresAt} but before the sweeper still confirms: the seats were never
     * released. Must run inside the caller's transaction (the payment row commits with it).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConfirmResult confirmPayment(long bookingId) {
        Booking booking = bookings.findForUpdateById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return ConfirmResult.ALREADY_CONFIRMED;
        }
        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING) {
            log.warn("Payment captured for booking {} in status {}: refund needed", booking.getPublicId(),
                    booking.getStatus());
            return ConfirmResult.NOT_CONFIRMABLE;
        }
        List<Long> seatIds = booking.getSeats().stream().filter(BookingSeat::isActive).map(BookingSeat::getSeatId).toList();
        booking.confirm(clock.instant());
        int booked = seatStatus.markBooked(booking.getShowId(), seatIds);
        if (booked != seatIds.size()) {
            // A PAYMENT_PENDING booking owns HELD seats (same transaction rules everywhere); anything else is a bug.
            throw new IllegalStateException("Booking " + booking.getPublicId() + ": only " + booked + "/"
                    + seatIds.size() + " seats were HELD");
        }
        log.info("Booking {} confirmed ({} seats)", booking.getPublicId(), seatIds.size());
        return ConfirmResult.CONFIRMED;
    }

    /** True if the booking (internal id) belongs to the user: ownership check for payment callbacks. */
    public boolean isOwnedBy(long bookingId, long userId) {
        return bookings.findById(bookingId).map(b -> b.getUserId() == userId).orElse(false);
    }

    /** Public id of a booking known by its internal id (payment module views). */
    public UUID publicIdOf(long bookingId) {
        return bookings.findById(bookingId).map(Booking::getPublicId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
    }

    private PayableBooking payable(Booking booking) {
        if (booking.getStatus() == BookingStatus.EXPIRED) {
            throw new BookingExpiredException();
        }
        booking.requirePayable(); // CANCELLED / CONFIRMED / REFUNDED → 409 INVALID_BOOKING_TRANSITION
        if (!clock.instant().isBefore(booking.getExpiresAt())) {
            throw new BookingExpiredException(); // deadline passed, sweeper hasn't run yet
        }
        return new PayableBooking(booking.getId(), booking.getPublicId(), booking.getStatus(),
                BookingPricing.amountDue(booking), booking.getCurrency(), booking.getExpiresAt());
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
