package com.seatlock.booking.domain;

import com.seatlock.common.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bookings")
public class Booking extends AuditableEntity {

    /** Random, non-enumerable ID exposed in APIs instead of the numeric PK. */
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    /** Cross-module references (identity, catalog) are IDs, not associations. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    /** The Redis hold this booking came from; UNIQUE in the DB, so a hold becomes at most one booking. */
    @Column(name = "hold_id", nullable = false, updatable = false)
    private UUID holdId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "total_paise", nullable = false)
    private long totalPaise;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Detects races between state transitions (e.g. payment webhook vs. expiry sweeper). */
    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    private List<BookingSeat> seats = new ArrayList<>();

    protected Booking() {
        // for JPA
    }

    public Booking(Long userId, Long showId, UUID holdId, Instant expiresAt) {
        this.publicId = UUID.randomUUID();
        this.userId = userId;
        this.showId = showId;
        this.holdId = holdId;
        this.expiresAt = expiresAt;
        this.status = BookingStatus.HELD;
        this.currency = "INR";
    }

    /** Adds a seat at the price charged now; the total is kept in sync (overflow-safe). */
    public void addSeat(long seatId, long pricePaise) {
        seats.add(new BookingSeat(this, showId, seatId, pricePaise));
        totalPaise = Math.addExact(totalPaise, pricePaise);
    }

    // ------------------------------------------------------------------ lifecycle (State pattern)

    /** HELD → PAYMENT_PENDING: a payment order was created (W4). */
    public void startPayment(Instant now) { apply(state().startPayment(this, now)); }

    /** PAYMENT_PENDING → CONFIRMED: payment verified. */
    public void confirm(Instant now) { apply(state().confirm(this, now)); }

    /** HELD / PAYMENT_PENDING → EXPIRED once {@code expiresAt} has passed; frees the seats. */
    public void expire(Instant now) { apply(state().expire(this, now)); }

    /** HELD / PAYMENT_PENDING → CANCELLED by the user; frees the seats. */
    public void cancel(Instant now) { apply(state().cancel(this, now)); }

    /** CONFIRMED → REFUNDED; frees the seats so they can be sold again. */
    public void refund(Instant now) { apply(state().refund(this, now)); }

    /**
     * A (new) payment attempt may start: HELD (first attempt) or PAYMENT_PENDING (retry after a failed attempt).
     * Anything else → 409 {@code INVALID_BOOKING_TRANSITION}. Doesn't check the deadline (the service does, with its clock).
     */
    public void requirePayable() {
        if (status != BookingStatus.HELD && status != BookingStatus.PAYMENT_PENDING) {
            throw IllegalBookingTransitionException.of(status, "startPayment");
        }
    }

    /** True while the booking still claims its seats (HELD, PAYMENT_PENDING, CONFIRMED). */
    public boolean isLive() {
        return status == BookingStatus.HELD || status == BookingStatus.PAYMENT_PENDING || status == BookingStatus.CONFIRMED;
    }

    private BookingState state() {
        return BookingState.of(status);
    }

    private void apply(BookingState next) {
        this.status = next.status();
    }

    // Side effects, called only by the state objects (same package).

    BookingState expireAt(Instant now) {
        if (now.isBefore(expiresAt)) {
            throw IllegalBookingTransitionException.of(status, "expire before " + expiresAt);
        }
        releaseSeats();
        return BookingState.Terminal.EXPIRED;
    }

    BookingState cancelAt(Instant now) {
        cancelledAt = now;
        releaseSeats();
        return BookingState.Terminal.CANCELLED;
    }

    BookingState confirmAt(Instant now) {
        confirmedAt = now;
        return BookingState.Confirmed.INSTANCE;
    }

    BookingState refundAt(Instant now) {
        cancelledAt = now;
        releaseSeats();
        return BookingState.Terminal.REFUNDED;
    }

    /** Deactivated rows drop out of ux_booking_seats_live_seat, so the seats can be sold again. */
    private void releaseSeats() {
        seats.forEach(BookingSeat::release);
    }

    public UUID getPublicId() { return publicId; }
    public Long getUserId() { return userId; }
    public Long getShowId() { return showId; }
    public UUID getHoldId() { return holdId; }
    public BookingStatus getStatus() { return status; }
    public long getTotalPaise() { return totalPaise; }
    public String getCurrency() { return currency; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public long getVersion() { return version; }
    public List<BookingSeat> getSeats() { return Collections.unmodifiableList(seats); }
}