package com.seatlock.catalog.domain;

import com.seatlock.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A seat for a specific show: its price, live status and optimistic-lock version. */
@Entity
@Table(name = "show_seats")
public class ShowSeat extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShowSeatStatus status;

    /** Used by the Optimistic locking strategy (W4·D6): concurrent updates fail instead of overwriting. */
    @Version
    @Column(nullable = false)
    private long version;

    protected ShowSeat() {
        // for JPA
    }

    public ShowSeat(Show show, Seat seat, long pricePaise) {
        this.show = show;
        this.seat = seat;
        this.pricePaise = pricePaise;
        this.status = ShowSeatStatus.AVAILABLE;
    }

    public Show getShow() { return show; }
    public Seat getSeat() { return seat; }
    public long getPricePaise() { return pricePaise; }
    public ShowSeatStatus getStatus() { return status; }
    public long getVersion() { return version; }
}