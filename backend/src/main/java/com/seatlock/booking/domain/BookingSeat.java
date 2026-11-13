package com.seatlock.booking.domain;

import com.seatlock.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One seat claimed by a booking. While {@code active}, the partial unique index
 * ux_booking_seats_live_seat guarantees no other live booking can hold the same seat.
 */
@Entity
@Table(name = "booking_seats")
public class BookingSeat extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Column(nullable = false)
    private boolean active;

    protected BookingSeat() {
        // for JPA
    }

    BookingSeat(Booking booking, Long showId, Long seatId, long pricePaise) {
        this.booking = booking;
        this.showId = showId;
        this.seatId = seatId;
        this.pricePaise = pricePaise;
        this.active = true;
    }

    public Booking getBooking() { return booking; }
    public Long getShowId() { return showId; }
    public Long getSeatId() { return seatId; }
    public long getPricePaise() { return pricePaise; }
    public boolean isActive() { return active; }
}