package com.seatlock.booking.application;

import com.seatlock.booking.domain.Booking;
import com.seatlock.booking.domain.BookingRepository;
import com.seatlock.booking.domain.BookingSeat;
import com.seatlock.catalog.application.ShowSeatStatusService;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Expires one batch of unpaid bookings per transaction (called by {@link BookingExpirySweeper}). */
@Service
public class BookingExpiryService {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryService.class);

    private final BookingRepository bookings;
    private final ShowSeatStatusService seatStatus;

    public BookingExpiryService(BookingRepository bookings, ShowSeatStatusService seatStatus) {
        this.bookings = bookings;
        this.seatStatus = seatStatus;
    }

    /**
     * Locks up to {@code limit} expired HELD/PAYMENT_PENDING bookings (SKIP LOCKED), moves each to EXPIRED through
     * the state machine (deactivating its booking_seats) and sets the seats AVAILABLE again, all in one commit.
     * @return how many bookings were expired
     */
    @Transactional
    public int expireBatch(Instant now, int limit) {
        List<Long> ids = bookings.lockExpiredIds(now, limit);
        if (ids.isEmpty()) {
            return 0;
        }
        for (Booking booking : bookings.findAllWithSeatsByIdIn(ids)) {
            List<Long> seatIds = booking.getSeats().stream().map(BookingSeat::getSeatId).toList();
            booking.expire(now); // state machine: only HELD/PAYMENT_PENDING may expire, never before expiresAt
            int freed = seatStatus.markAvailable(booking.getShowId(), seatIds);
            if (freed != seatIds.size()) {
                log.warn("Booking {} expired but only {}/{} seats were HELD in show_seats", booking.getPublicId(),
                        freed, seatIds.size());
            }
        }
        return ids.size(); // dirty entities (status, booking_seats.active, @Version) flush at commit
    }
}
